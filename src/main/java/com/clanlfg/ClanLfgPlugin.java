package com.clanlfg;

import com.google.inject.Provides;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@PluginDescriptor(
	name = "Clan LFG",
	description = "Tell your clan or friends group what content you're up for, and see who else is. Needs a group name and password from your group's admin, and shares your choices with the group through the plugin's server.",
	tags = {"clan", "lfg", "group", "raids", "social", "friends", "looking for group"}
)
public class ClanLfgPlugin extends Plugin implements ClanLfgPanel.Callbacks, LfgSession.Listener
{
	private static final Logger log = LoggerFactory.getLogger(ClanLfgPlugin.class);

	private static final String KEY_TAGS = "tags";
	private static final String KEY_DURATION = "duration";
	private static final String KEY_STATUS = "status";
	private static final String KEY_COLLAPSED = "collapsed";
	private static final String KEY_ALERTS = "chatAlerts";

	@Inject
	private Client client;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ConfigManager configManager;

	@Inject
	private ItemManager itemManager;

	@Inject
	private ChatMessageManager chatMessageManager;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ClanLfgConfig config;

	@Inject
	private OkHttpClient okHttpClient;

	@Inject
	private ScheduledExecutorService executor;

	private ClanLfgPanel panel;
	private NavigationButton navButton;
	private LfgSession session;
	private ScheduledFuture<?> tickTask;
	private ScheduledFuture<?> pendingPush;

	// Preferences chosen in the panel
	private final Set<Tag> myTags = EnumSet.noneOf(Tag.class);
	private final Set<Tag.Category> collapsed = EnumSet.noneOf(Tag.Category.class); // groups the user closed
	private VisibleFor visibleFor = VisibleFor.HOURS_2;
	private volatile String status = "";
	private volatile boolean chatAlerts = true; // show a line in the player's own chat when someone opens up for what they picked
	private final OptInAlerts alerts = new OptInAlerts();
	private volatile boolean visible;
	private volatile long visibleUntil; // local epoch ms, 0 = until logout

	// Login tracking (only touched on the client thread, except where noted)
	private boolean wantStart;
	private String sessionRsn;
	private volatile int currentWorld;

	@Provides
	ClanLfgConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(ClanLfgConfig.class);
	}

	// ------------------------------------------------------------------ lifecycle

	@Override
	protected void startUp()
	{
		loadPreferences();
		Set<Tag.Category> closedGroups;
		synchronized (collapsed)
		{
			closedGroups = EnumSet.copyOf(collapsed.isEmpty() ? EnumSet.noneOf(Tag.Category.class) : collapsed);
		}
		panel = new ClanLfgPanel(this, myTags, visibleFor, status, closedGroups, chatAlerts, new ItemTagIcons(itemManager));
		session = new LfgSession(okHttpClient, ServerConfig.baseUrl(), executor, this);

		navButton = NavigationButton.builder()
			.tooltip("Clan LFG")
			.icon(buildIcon())
			.priority(7)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		tickTask = executor.scheduleWithFixedDelay(this::tick, 15, 15, TimeUnit.SECONDS);

		// Plugin switched on while already logged in.
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			wantStart = true;
		}
		else
		{
			panel.setState(LfgSession.State.IDLE, "");
		}
	}

	@Override
	protected void shutDown()
	{
		if (tickTask != null)
		{
			tickTask.cancel(false);
		}
		if (pendingPush != null)
		{
			pendingPush.cancel(false);
		}
		if (session != null)
		{
			session.stop();
		}
		clientToolbar.removeNavigation(navButton);
		if (panel != null)
		{
			panel.dispose();
		}
		panel = null;
		session = null;
		navButton = null;
		sessionRsn = null;
		wantStart = false;
		visible = false;
		alerts.reset();
	}

	// ------------------------------------------------------------------ game events (client thread)

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState gs = event.getGameState();
		if (gs == GameState.LOGGED_IN)
		{
			wantStart = true; // handled on the next game tick, once the player's name is available
		}
		else if (gs == GameState.LOGIN_SCREEN || gs == GameState.CONNECTION_LOST)
		{
			wantStart = false;
			sessionRsn = null;
			visible = false; // never stay visible across a logout
			if (session != null)
			{
				session.stop();
			}
			if (panel != null)
			{
				panel.setVisibleChecked(false);
			}
		}
		// HOPPING / LOADING: keep the connection, the world number is refreshed after we land.
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		currentWorld = client.getWorld();
		if (!wantStart || session == null)
		{
			return;
		}
		Player me = client.getLocalPlayer();
		if (me == null || me.getName() == null)
		{
			return;
		}
		wantStart = false;
		String rsn = me.getName().replace(' ', ' ').trim();
		if (rsn.equalsIgnoreCase(sessionRsn))
		{
			pushPresence(); // same account, probably a world hop: just update the world number
			return;
		}
		startSession(rsn);
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (ClanLfgConfig.GROUP.equals(event.getGroup())
			&& ("groupName".equals(event.getKey()) || "groupPassword".equals(event.getKey())))
		{
			// New group or password: reconnect on the next game tick (needs the client thread to read the name).
			sessionRsn = null;
			if (client.getGameState() == GameState.LOGGED_IN)
			{
				wantStart = true;
			}
		}
	}

	private void startSession(String rsn)
	{
		sessionRsn = rsn;
		visible = config.autoShow();
		visibleUntil = visible ? untilFromNow() : 0;
		if (panel != null)
		{
			panel.setVisibleChecked(visible);
		}
		String token = configManager.getConfiguration(ClanLfgConfig.GROUP, tokenKey(rsn));
		session.start(config.groupName(), config.groupPassword(), rsn, token);
		pushPresence();
	}

	// ------------------------------------------------------------------ panel callbacks (Swing thread)

	@Override
	public void onTagsChanged(Set<Tag> tags)
	{
		synchronized (myTags)
		{
			myTags.clear();
			myTags.addAll(tags);
		}
		savePreferences();
		pushPresenceSoon();
	}

	@Override
	public void onStatusChanged(String newStatus)
	{
		status = Status.clean(newStatus);
		savePreferences();
		pushPresenceSoon();
	}

	@Override
	public void onAlertsChanged(boolean enabled)
	{
		chatAlerts = enabled;
		savePreferences();
	}

	@Override
	public void onCollapsedChanged(Set<Tag.Category> nowCollapsed)
	{
		synchronized (collapsed)
		{
			collapsed.clear();
			collapsed.addAll(nowCollapsed);
		}
		savePreferences();
	}

	@Override
	public void onVisibleChanged(boolean nowVisible)
	{
		visible = nowVisible;
		visibleUntil = nowVisible ? untilFromNow() : 0;
		pushPresence();
	}

	@Override
	public void onDurationChanged(VisibleFor duration)
	{
		visibleFor = duration;
		savePreferences();
		if (visible)
		{
			visibleUntil = untilFromNow();
			pushPresence();
		}
	}

	@Override
	public void onJoin(String groupName, String password)
	{
		// Saving these triggers onConfigChanged, which reconnects.
		configManager.setConfiguration(ClanLfgConfig.GROUP, "groupName", groupName.trim());
		configManager.setConfiguration(ClanLfgConfig.GROUP, "groupPassword", password.trim());
	}

	@Override
	public void onLeaveGroup()
	{
		if (session == null)
		{
			return;
		}
		session.leave(ok ->
		{
			if (ok)
			{
				// Forget the group too, otherwise we would silently rejoin on the next login.
				configManager.unsetConfiguration(ClanLfgConfig.GROUP, "groupName");
				configManager.unsetConfiguration(ClanLfgConfig.GROUP, "groupPassword");
				sessionRsn = null;
				if (panel != null)
				{
					panel.setState(LfgSession.State.NEEDS_CODE, "You left the group and your data was deleted.");
				}
			}
			else if (panel != null)
			{
				panel.setState(LfgSession.State.OFFLINE, "Couldn't reach the server to leave. Try again.");
			}
		});
	}

	// ------------------------------------------------------------------ session callbacks (network threads)

	@Override
	public void onState(LfgSession.State state, String message)
	{
		switch (state)
		{
			case IDLE:
			case MISCONFIGURED:
			case OUTDATED:
			case NEEDS_CODE:
			case BAD_CODE:
			case NAME_TAKEN:
			case REMOVED:
				alerts.reset(); // not in a group any more: start fresh next time, with no alerts for people already there
				break;
			default:
				break;
		}
		if (panel != null)
		{
			panel.setState(state, message);
		}
	}

	@Override
	public void onMembers(List<Member> others, long clockOffsetMs)
	{
		if (panel != null)
		{
			panel.setMembers(others, clockOffsetMs);
		}

		Set<Tag> picked = EnumSet.noneOf(Tag.class);
		synchronized (myTags)
		{
			picked.addAll(myTags);
		}
		long now = System.currentTimeMillis();
		List<OptInAlerts.Alert> found = alerts.update(others, picked, now + clockOffsetMs, now);
		log.debug("members update: {} visible, {} picked, {} new opt-ins, chat alerts {}",
			others.size(), picked.size(), found.size(), chatAlerts ? "on" : "off");
		if (chatAlerts && !found.isEmpty())
		{
			postAlerts(found);
		}
	}

	/**
	 * Shows a line in YOUR chat box (only you see it, nothing is sent to anyone or to the server)
	 * for each person who has just opened up for something you picked.
	 */
	private void postAlerts(List<OptInAlerts.Alert> found)
	{
		clientThread.invoke(() ->
		{
			if (client.getGameState() != GameState.LOGGED_IN)
			{
				return;
			}
			for (OptInAlerts.Alert a : found)
			{
				chatMessageManager.queue(QueuedMessage.builder()
					.type(ChatMessageType.CONSOLE)
					.runeLiteFormattedMessage(alertText(a))
					.build());
			}
		});
	}

	/** Builds the chat line. Names and messages come from the server, so angle brackets are removed first. */
	static String alertText(OptInAlerts.Alert alert)
	{
		List<Tag> tags = alert.getTags();
		StringBuilder what = new StringBuilder();
		for (int i = 0; i < tags.size() && i < 3; i++)
		{
			if (i > 0)
			{
				what.append(", ");
			}
			what.append(tags.get(i).getFullName().replaceAll("\\s*\\(.*\\)", ""));
		}
		if (tags.size() > 3)
		{
			what.append(" +").append(tags.size() - 3).append(" more");
		}
		StringBuilder line = new StringBuilder("<col=ff9040>Clan LFG:</col> <col=ffffff>")
			.append(plain(alert.getMember().getRsn())).append("</col> is now open to ").append(what);
		if (!alert.getMember().getStatus().isEmpty())
		{
			line.append(" - \"").append(plain(alert.getMember().getStatus())).append('"');
		}
		return line.toString();
	}

	private static String plain(String s)
	{
		return s.replace("<", "").replace(">", "");
	}

	@Override
	public void onTokenIssued(String rsn, String token)
	{
		configManager.setConfiguration(ClanLfgConfig.GROUP, tokenKey(rsn), token);
	}

	@Override
	public void onTokenRejected(String rsn)
	{
		configManager.unsetConfiguration(ClanLfgConfig.GROUP, tokenKey(rsn));
	}

	// ------------------------------------------------------------------ helpers

	/** Runs every 15 seconds on the plugin's own thread. */
	private void tick()
	{
		try
		{
			long until = visibleUntil;
			if (visible && until > 0 && System.currentTimeMillis() >= until)
			{
				visible = false;
				visibleUntil = 0;
				if (panel != null)
				{
					panel.setVisibleChecked(false);
				}
				pushPresence();
			}
		}
		catch (RuntimeException e)
		{
			log.debug("tick failed: {}", e.toString());
		}
	}

	private long untilFromNow()
	{
		int minutes = visibleFor.getMinutes();
		return minutes == 0 ? 0 : System.currentTimeMillis() + minutes * 60_000L;
	}

	/** Tag clicks can come fast; wait half a second so we send one update, not ten. */
	private void pushPresenceSoon()
	{
		if (pendingPush != null)
		{
			pendingPush.cancel(false);
		}
		pendingPush = executor.schedule(this::pushPresence, 500, TimeUnit.MILLISECONDS);
	}

	private void pushPresence()
	{
		LfgSession s = session;
		if (s == null)
		{
			return;
		}
		List<String> ids = new ArrayList<>();
		synchronized (myTags)
		{
			for (Tag t : myTags)
			{
				ids.add(t.getId());
			}
		}
		s.setPresence(visible, ids, visibleFor.getMinutes(), currentWorld, status);
	}

	private void loadPreferences()
	{
		synchronized (myTags)
		{
			myTags.clear();
			String saved = configManager.getConfiguration(ClanLfgConfig.GROUP, KEY_TAGS);
			if (saved != null && !saved.isEmpty())
			{
				for (String id : saved.split(","))
				{
					Tag t = Tag.fromId(id.trim());
					if (t != null)
					{
						myTags.add(t);
					}
				}
			}
		}
		status = Status.clean(configManager.getConfiguration(ClanLfgConfig.GROUP, KEY_STATUS));
		String savedAlerts = configManager.getConfiguration(ClanLfgConfig.GROUP, KEY_ALERTS);
		chatAlerts = savedAlerts == null || Boolean.parseBoolean(savedAlerts);
		synchronized (collapsed)
		{
			collapsed.clear();
			String closed = configManager.getConfiguration(ClanLfgConfig.GROUP, KEY_COLLAPSED);
			if (closed != null && !closed.isEmpty())
			{
				for (String name : closed.split(","))
				{
					try
					{
						collapsed.add(Tag.Category.valueOf(name.trim()));
					}
					catch (IllegalArgumentException e)
					{
						// a group this version no longer has: ignore it
					}
				}
			}
		}
		String d = configManager.getConfiguration(ClanLfgConfig.GROUP, KEY_DURATION);
		try
		{
			visibleFor = d == null ? VisibleFor.HOURS_2 : VisibleFor.valueOf(d);
		}
		catch (IllegalArgumentException e)
		{
			visibleFor = VisibleFor.HOURS_2;
		}
	}

	private void savePreferences()
	{
		StringBuilder sb = new StringBuilder();
		synchronized (myTags)
		{
			for (Tag t : myTags)
			{
				if (sb.length() > 0)
				{
					sb.append(',');
				}
				sb.append(t.getId());
			}
		}
		configManager.setConfiguration(ClanLfgConfig.GROUP, KEY_TAGS, sb.toString());
		configManager.setConfiguration(ClanLfgConfig.GROUP, KEY_DURATION, visibleFor.name());
		configManager.setConfiguration(ClanLfgConfig.GROUP, KEY_STATUS, status);
		configManager.setConfiguration(ClanLfgConfig.GROUP, KEY_ALERTS, Boolean.toString(chatAlerts));
		StringBuilder closed = new StringBuilder();
		synchronized (collapsed)
		{
			for (Tag.Category c : collapsed)
			{
				if (closed.length() > 0)
				{
					closed.append(',');
				}
				closed.append(c.name());
			}
		}
		configManager.setConfiguration(ClanLfgConfig.GROUP, KEY_COLLAPSED, closed.toString());
	}

	/** One saved token per player name, so alts work. Only letters and digits end up in the key. */
	static String tokenKey(String rsn)
	{
		return "token_" + rsn.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "_");
	}

	/** A tiny two-people icon drawn in code, so the plugin needs no image files. */
	private static BufferedImage buildIcon()
	{
		BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(new Color(0xDC, 0x8A, 0x00));
		g.fillOval(2, 2, 6, 6);
		g.fillRoundRect(0, 9, 10, 6, 6, 6);
		g.setColor(new Color(0xC6, 0xC6, 0xC6));
		g.fillOval(9, 4, 5, 5);
		g.fillRoundRect(8, 10, 8, 5, 5, 5);
		g.dispose();
		return img;
	}
}
