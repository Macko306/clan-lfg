package com.clanlfg;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Everything that talks to the server: joining with the group's name and password, holding the live WebSocket,
 * reconnecting after drops, and keeping the list of visible members.
 *
 * This class knows nothing about RuneLite or Swing, so it can be tested on its own.
 *
 * Threading: methods may be called from any thread. Callbacks to the Listener happen on OkHttp or
 * scheduler threads, never while holding the internal lock. Every network callback carries the
 * "generation" it was started in; if start()/stop() has been called since, the callback is ignored.
 */
public class LfgSession
{
	private static final Logger log = LoggerFactory.getLogger(LfgSession.class);

	public enum State
	{
		IDLE, MISCONFIGURED, OUTDATED, JOINING, CONNECTING, CONNECTED, PENDING, NEEDS_CODE, BAD_CODE, NAME_TAKEN, REMOVED, REPLACED, OFFLINE
	}

	public interface Listener
	{
		void onState(State state, String message);

		/** Everyone currently visible except you. clockOffsetMs = serverTime - localTime. */
		void onMembers(List<Member> others, long clockOffsetMs);

		/** Save this token (per player name) so we do not have to join again. */
		void onTokenIssued(String rsn, String token);

		/** The saved token is no longer good. Delete it. */
		void onTokenRejected(String rsn);
	}

	/** The version of the conversation with the server (see "Changing the protocol" in the server's README). Sent with every request. */
	static final int PROTOCOL_VERSION = 1;
	private static final String PROTOCOL_HEADER = "X-ClanLFG-Protocol";
	private static final String OUTDATED_MESSAGE = "This version of the plugin is too old for the server. Please update it from the Plugin Hub.";

	private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
	private static final long MIN_BACKOFF_MS = 2_000;
	private static final long MAX_BACKOFF_MS = 60_000;
	/** How often a not-yet-approved member re-checks. Not final so tests can shorten it. */
	static volatile long pendingRetryMs = 60_000;
	private static final long RATE_LIMIT_RETRY_MS = 5 * 60_000;
	private static final int MAX_MEMBERS = 2000;

	private final OkHttpClient http;
	private final String baseUrl;
	private final ScheduledExecutorService scheduler;
	private final Listener listener;
	private final Object lock = new Object();

	// ---- everything below is guarded by `lock`
	private long generation;
	private String groupName = ""; // as typed, e.g. "My Clan"
	private String groupSlug; // normalised, e.g. "myclan"; null if what was typed can't be a group
	private String password = "";
	private String rsn;
	private String token;
	private String selfId;
	private boolean triedRejoin;
	private boolean noReconnect;
	private boolean connected;
	private WebSocket socket;
	private ScheduledFuture<?> retryTask;
	private long backoffMs = MIN_BACKOFF_MS;
	private long clockOffsetMs;
	private final Map<String, Member> members = new LinkedHashMap<>();

	private boolean wantVisible;
	private List<String> wantTags = Collections.emptyList();
	private int wantMinutes;
	private int wantWorld;
	private String wantStatus = "";

	public LfgSession(OkHttpClient baseClient, String baseUrl, ScheduledExecutorService scheduler, Listener listener)
	{
		// Protocol-level pings keep the connection alive and detect dead ones. They are answered by
		// the platform and are not billed as requests.
		this.http = baseClient.newBuilder()
			.pingInterval(25, TimeUnit.SECONDS)
			.readTimeout(0, TimeUnit.MILLISECONDS)
			.build();
		this.baseUrl = baseUrl;
		this.scheduler = scheduler;
		this.listener = listener;
	}

	// ------------------------------------------------------------------ public API

	/** Starts (or restarts) a session for this player. savedToken may be null. */
	public void start(String group, String groupPassword, String playerName, String savedToken)
	{
		final long gen;
		String usableToken = savedToken;
		boolean dropSavedToken = false;
		synchronized (lock)
		{
			teardownLocked();
			gen = ++generation;
			groupName = group == null ? "" : group.trim();
			groupSlug = GroupName.slug(groupName);
			password = groupPassword == null ? "" : groupPassword.trim();
			rsn = playerName;
			if (usableToken != null && !tokenMatchesGroup(usableToken, groupSlug))
			{
				// The saved token belongs to a different group than the one now in settings.
				dropSavedToken = true;
				usableToken = null;
			}
			token = usableToken;
			triedRejoin = false;
			noReconnect = false;
			backoffMs = MIN_BACKOFF_MS;
		}
		if (dropSavedToken)
		{
			listener.onTokenRejected(playerName);
		}
		if (!ServerConfig.isUsable(baseUrl))
		{
			emit(gen, State.MISCONFIGURED, "The server address isn't set up in this build of the plugin.");
			return;
		}
		if (groupName.isEmpty())
		{
			emit(gen, State.NEEDS_CODE, "Enter your group's name to join.");
			return;
		}
		if (groupSlug == null)
		{
			emit(gen, State.BAD_CODE, "That doesn't look like a group name. Type it the way your clan calls it, for example My Clan.");
			return;
		}
		if (usableToken == null)
		{
			join(gen);
		}
		else
		{
			connect(gen);
		}
	}

	/** Disconnects and forgets everything about the current session (the saved token is kept). */
	public void stop()
	{
		final long gen;
		synchronized (lock)
		{
			teardownLocked();
			gen = ++generation;
			members.clear();
		}
		emitMembers(gen);
		emit(gen, State.IDLE, "Log in to connect.");
	}

	/** Sets what we want to show to the group. Remembered, and re-sent on every reconnect. */
	public void setPresence(boolean visible, List<String> tagIds, int minutes, int world, String status)
	{
		synchronized (lock)
		{
			wantVisible = visible;
			wantTags = new ArrayList<>(tagIds);
			wantMinutes = minutes;
			wantWorld = world;
			wantStatus = Status.clean(status);
			sendPresenceLocked();
		}
	}

	/** Deletes your membership (and all data about you) on the server, then disconnects. */
	public void leave(Consumer<Boolean> done)
	{
		final String t;
		final String name;
		synchronized (lock)
		{
			t = token;
			name = rsn;
		}
		if (t == null)
		{
			done.accept(true);
			return;
		}
		Request req = new Request.Builder()
			.url(baseUrl + "/v1/leave")
			.header("Authorization", "Bearer " + t)
			.post(RequestBody.create(JSON, "{}"))
			.build();
		http.newCall(req).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				done.accept(false);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				int status;
				try (Response r = response)
				{
					status = r.code();
				}
				// 200 = deleted, 401 = the server did not know us anyway. Either way we are gone.
				boolean ok = status == 200 || status == 401;
				if (ok)
				{
					stop();
					listener.onTokenRejected(name);
				}
				done.accept(ok);
			}
		});
	}

	// ------------------------------------------------------------------ joining

	private void join(final long gen)
	{
		final String joinGroup;
		final String joinPassword;
		final String name;
		synchronized (lock)
		{
			if (gen != generation)
			{
				return;
			}
			joinGroup = groupName;
			joinPassword = password;
			name = rsn;
		}
		emit(gen, State.JOINING, "Joining group…");

		JsonObject body = new JsonObject();
		body.addProperty("group", joinGroup);
		body.addProperty("password", joinPassword);
		body.addProperty("rsn", name);
		Request req = new Request.Builder()
			.url(baseUrl + "/v1/join")
			.header(PROTOCOL_HEADER, String.valueOf(PROTOCOL_VERSION))
			.post(RequestBody.create(JSON, body.toString()))
			.build();
		http.newCall(req).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				if (!isCurrent(gen))
				{
					return;
				}
				emit(gen, State.OFFLINE, "Can't reach the server. Retrying…");
				scheduleRetry(gen, nextBackoff(gen), () -> join(gen));
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				int status;
				String text;
				try (Response r = response)
				{
					status = r.code();
					text = r.body() == null ? "" : r.body().string();
				}
				catch (IOException e)
				{
					onFailure(call, e);
					return;
				}
				handleJoinResponse(gen, name, status, text);
			}
		});
	}

	private void handleJoinResponse(long gen, String name, int status, String text)
	{
		if (!isCurrent(gen))
		{
			return;
		}
		JsonObject obj = parseObject(text);
		if (status == 200 && obj != null && str(obj, "token") != null)
		{
			String newToken = str(obj, "token");
			synchronized (lock)
			{
				token = newToken;
			}
			listener.onTokenIssued(name, newToken);
			connect(gen);
			return;
		}
		String error = obj == null ? "" : String.valueOf(str(obj, "error"));
		switch (error)
		{
			case "invalid_code":
			case "bad_request":
				emit(gen, State.BAD_CODE, "That group name or password isn't right. Check them and try again.");
				break;
			case "update_required":
				markOutdated(gen);
				break;
			case "name_taken":
				emit(gen, State.NAME_TAKEN, "This player name is already registered in the group. Ask a group admin to reset it.");
				break;
			case "rate_limited":
				emit(gen, State.OFFLINE, "Too many attempts. Trying again in a few minutes.");
				scheduleRetry(gen, RATE_LIMIT_RETRY_MS, () -> join(gen));
				break;
			case "group_full":
				emit(gen, State.OFFLINE, "This group is full.");
				scheduleRetry(gen, RATE_LIMIT_RETRY_MS, () -> join(gen));
				break;
			default:
				emit(gen, State.OFFLINE, "The server had a problem. Retrying…");
				scheduleRetry(gen, nextBackoff(gen), () -> join(gen));
				break;
		}
	}

	// ------------------------------------------------------------------ live connection

	private void connect(final long gen)
	{
		final String t;
		synchronized (lock)
		{
			if (gen != generation)
			{
				return;
			}
			t = token;
		}
		if (t == null)
		{
			join(gen);
			return;
		}
		emit(gen, State.CONNECTING, "Connecting…");
		String wsUrl = baseUrl.replaceFirst("^http", "ws") + "/v1/ws";
		Request req = new Request.Builder().url(wsUrl).header("Authorization", "Bearer " + t)
			.header(PROTOCOL_HEADER, String.valueOf(PROTOCOL_VERSION)).build();
		WebSocket ws = http.newWebSocket(req, new SocketEvents(gen));
		synchronized (lock)
		{
			if (gen != generation)
			{
				ws.cancel();
				return;
			}
			socket = ws;
		}
	}

	private final class SocketEvents extends WebSocketListener
	{
		private final long gen;

		SocketEvents(long gen)
		{
			this.gen = gen;
		}

		@Override
		public void onOpen(WebSocket ws, Response response)
		{
			adoptSocket(gen, ws);
		}

		@Override
		public void onMessage(WebSocket ws, String text)
		{
			if (isCurrent(gen))
			{
				adoptSocket(gen, ws);
				handleMessage(gen, ws, text);
			}
		}

		@Override
		public void onClosing(WebSocket ws, int code, String reason)
		{
			ws.close(1000, null);
		}

		@Override
		public void onClosed(WebSocket ws, int code, String reason)
		{
			if (isCurrent(gen))
			{
				handleDisconnect(gen);
			}
		}

		@Override
		public void onFailure(WebSocket ws, Throwable t, Response response)
		{
			if (!isCurrent(gen))
			{
				return;
			}
			if (response == null)
			{
				handleDisconnect(gen);
				return;
			}
			int status = response.code();
			String body = "";
			try (Response r = response)
			{
				body = r.body() == null ? "" : r.body().string();
			}
			catch (IOException ignored)
			{
				// fall through with an empty body
			}
			handleHandshakeFailure(gen, status, body);
		}
	}

	/**
	 * connect() stores the socket right after creating it, but the server's first message can in
	 * principle beat that assignment. Whichever happens first wins; both store the same object.
	 */
	private void adoptSocket(long gen, WebSocket ws)
	{
		synchronized (lock)
		{
			if (gen == generation && socket == null)
			{
				socket = ws;
			}
		}
	}

	/** The server says this plugin is too old. Retrying cannot help, so stop and say what to do. */
	private void markOutdated(long gen)
	{
		synchronized (lock)
		{
			noReconnect = true;
		}
		emit(gen, State.OUTDATED, OUTDATED_MESSAGE);
	}

	private void handleHandshakeFailure(long gen, int status, String body)
	{
		JsonObject obj = parseObject(body);
		String error = obj == null ? "" : String.valueOf(str(obj, "error"));
		if (status == 426 && "update_required".equals(error))
		{
			markOutdated(gen);
			return;
		}
		if (status == 401)
		{
			// Our saved token is not valid any more (group reset, data deleted...). Try joining once more.
			final String name;
			final boolean retryJoin;
			synchronized (lock)
			{
				name = rsn;
				token = null;
				retryJoin = !triedRejoin;
				triedRejoin = true;
			}
			listener.onTokenRejected(name);
			if (retryJoin)
			{
				join(gen);
			}
			else
			{
				emit(gen, State.OFFLINE, "Couldn't sign in. Retrying…");
				scheduleRetry(gen, nextBackoff(gen), () -> join(gen));
			}
			return;
		}
		if (status == 403 && "pending".equals(error))
		{
			emit(gen, State.PENDING, "Waiting for a group admin to approve you.");
			scheduleRetry(gen, pendingRetryMs, () -> connect(gen));
			return;
		}
		if (status == 403 && "removed".equals(error))
		{
			synchronized (lock)
			{
				noReconnect = true;
			}
			emit(gen, State.REMOVED, "You were removed from this group.");
			return;
		}
		emit(gen, State.OFFLINE, "Can't reach the server. Retrying…");
		scheduleRetry(gen, nextBackoff(gen), () -> connect(gen));
	}

	private void handleDisconnect(long gen)
	{
		final boolean retry;
		synchronized (lock)
		{
			connected = false;
			socket = null;
			members.clear();
			retry = !noReconnect;
		}
		emitMembers(gen);
		if (retry)
		{
			emit(gen, State.OFFLINE, "Connection lost. Reconnecting…");
			scheduleRetry(gen, nextBackoff(gen), () -> connect(gen));
		}
	}

	// ------------------------------------------------------------------ messages from the server

	private void handleMessage(long gen, WebSocket ws, String text)
	{
		JsonObject msg = parseObject(text);
		String type = msg == null ? null : str(msg, "t");
		if (type == null)
		{
			return;
		}
		try
		{
			switch (type)
			{
				case "hello":
				{
					String groupName = str(msg, "group");
					synchronized (lock)
					{
						connected = true;
						backoffMs = MIN_BACKOFF_MS;
						selfId = str(msg, "you");
						clockOffsetMs = lng(msg, "now", System.currentTimeMillis()) - System.currentTimeMillis();
						replaceMembersLocked(msg.get("members"));
						sendPresenceLocked();
					}
					emit(gen, State.CONNECTED, groupName == null ? "Connected" : groupName);
					emitMembers(gen);
					break;
				}
				case "snapshot":
					synchronized (lock)
					{
						replaceMembersLocked(msg.get("members"));
					}
					emitMembers(gen);
					break;
				case "upsert":
				{
					Member m = parseMember(msg.get("m"));
					synchronized (lock)
					{
						if (m != null && !m.getId().equals(selfId) && members.size() < MAX_MEMBERS)
						{
							members.put(m.getId(), m);
						}
					}
					emitMembers(gen);
					break;
				}
				case "remove":
				{
					String id = str(msg, "id");
					synchronized (lock)
					{
						if (id != null)
						{
							members.remove(id);
						}
					}
					emitMembers(gen);
					break;
				}
				case "bye":
				{
					// The server ended our connection on purpose. Hang up and do NOT reconnect.
					String reason = str(msg, "reason");
					synchronized (lock)
					{
						noReconnect = true;
						members.clear();
					}
					ws.close(1000, null);
					emitMembers(gen);
					if ("replaced".equals(reason))
					{
						emit(gen, State.REPLACED, "You're connected from another client.");
					}
					else
					{
						emit(gen, State.REMOVED, "You were removed from this group.");
					}
					break;
				}
				default:
					log.debug("ignoring server message of type {}", type);
					break;
			}
		}
		catch (RuntimeException e)
		{
			// A malformed message must never take the plugin down.
			log.debug("bad server message: {}", e.toString());
		}
	}

	private void replaceMembersLocked(JsonElement list)
	{
		members.clear();
		if (list == null || !list.isJsonArray())
		{
			return;
		}
		for (JsonElement el : list.getAsJsonArray())
		{
			Member m = parseMember(el);
			if (m != null && !m.getId().equals(selfId) && members.size() < MAX_MEMBERS)
			{
				members.put(m.getId(), m);
			}
		}
	}

	/** Defensive parsing: anything unexpected means "skip this entry", never an exception. */
	static Member parseMember(JsonElement el)
	{
		if (el == null || !el.isJsonObject())
		{
			return null;
		}
		JsonObject o = el.getAsJsonObject();
		String id = str(o, "id");
		String name = str(o, "rsn");
		if (id == null || id.length() > 32 || name == null || name.isEmpty() || name.length() > 12)
		{
			return null;
		}
		List<Tag> tags = new ArrayList<>();
		JsonElement tagEl = o.get("tags");
		if (tagEl != null && tagEl.isJsonArray())
		{
			JsonArray arr = tagEl.getAsJsonArray();
			for (JsonElement t : arr)
			{
				if (t.isJsonPrimitive() && t.getAsJsonPrimitive().isString())
				{
					Tag tag = Tag.fromId(t.getAsString());
					if (tag != null && !tags.contains(tag))
					{
						tags.add(tag);
					}
				}
			}
		}
		if (tags.isEmpty())
		{
			return null;
		}
		return new Member(id, name, tags, (int) lng(o, "world", 0), lng(o, "expiresAt", 0), str(o, "status"));
	}

	// ------------------------------------------------------------------ helpers

	private void sendPresenceLocked()
	{
		if (socket == null || !connected)
		{
			return;
		}
		JsonObject o = new JsonObject();
		o.addProperty("t", "set");
		o.addProperty("visible", wantVisible && !wantTags.isEmpty());
		JsonArray tags = new JsonArray();
		for (String id : wantTags)
		{
			tags.add(id);
		}
		o.add("tags", tags);
		o.addProperty("minutes", wantMinutes);
		o.addProperty("world", wantWorld);
		o.addProperty("status", wantStatus);
		socket.send(o.toString());
	}

	private void teardownLocked()
	{
		if (retryTask != null)
		{
			retryTask.cancel(false);
			retryTask = null;
		}
		if (socket != null)
		{
			try
			{
				socket.close(1000, "bye");
			}
			catch (RuntimeException ignored)
			{
				// already closed
			}
			socket = null;
		}
		connected = false;
		selfId = null;
	}

	private boolean isCurrent(long gen)
	{
		synchronized (lock)
		{
			return gen == generation;
		}
	}

	private long nextBackoff(long gen)
	{
		synchronized (lock)
		{
			long base = backoffMs;
			backoffMs = Math.min(MAX_BACKOFF_MS, backoffMs * 2);
			// +/- 20% jitter so a whole clan does not reconnect in the same second after an outage
			double jitter = 0.8 + ThreadLocalRandom.current().nextDouble() * 0.4;
			return (long) (base * jitter);
		}
	}

	private void scheduleRetry(long gen, long delayMs, Runnable action)
	{
		synchronized (lock)
		{
			if (gen != generation)
			{
				return;
			}
			if (retryTask != null)
			{
				retryTask.cancel(false);
			}
			retryTask = scheduler.schedule(() ->
			{
				if (isCurrent(gen))
				{
					action.run();
				}
			}, delayMs, TimeUnit.MILLISECONDS);
		}
	}

	private void emit(long gen, State state, String message)
	{
		if (isCurrent(gen))
		{
			listener.onState(state, message);
		}
	}

	private void emitMembers(long gen)
	{
		final List<Member> copy;
		final long offset;
		synchronized (lock)
		{
			if (gen != generation)
			{
				return;
			}
			copy = new ArrayList<>(members.values());
			offset = clockOffsetMs;
		}
		listener.onMembers(copy, offset);
	}

	/** A saved token is only good for the group it was issued by (its second part is the group's short code). */
	private static boolean tokenMatchesGroup(String token, String groupSlug)
	{
		String[] parts = token.split("\\.");
		return parts.length == 4 && groupSlug != null && parts[1].equals(groupSlug);
	}

	static JsonObject parseObject(String text)
	{
		try
		{
			JsonElement el = new JsonParser().parse(text);
			return el.isJsonObject() ? el.getAsJsonObject() : null;
		}
		catch (RuntimeException e)
		{
			return null;
		}
	}

	static String str(JsonObject o, String key)
	{
		JsonElement el = o.get(key);
		return el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isString() ? el.getAsString() : null;
	}

	static long lng(JsonObject o, String key, long fallback)
	{
		JsonElement el = o.get(key);
		if (el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber())
		{
			return el.getAsLong();
		}
		return fallback;
	}
}
