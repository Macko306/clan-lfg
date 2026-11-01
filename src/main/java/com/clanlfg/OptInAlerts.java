package com.clanlfg;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Works out who has just opened up for content you are interested in, by comparing each member
 * list from the server with the previous one. No RuneLite or Swing code in here.
 *
 * Rules: the first list after connecting only sets the baseline (so you are not told about everyone
 * who was already online); you are only told about content you picked yourself; someone who stays
 * online and does not change is never repeated; and the same person and content is not announced
 * again within a few minutes, so logging in and out does not spam you.
 */
final class OptInAlerts
{
	static final long COOLDOWN_MS = 5 * 60_000;

	/** One person who is newly open to some of the content you picked. */
	static final class Alert
	{
		private final Member member;
		private final List<Tag> tags;

		Alert(Member member, List<Tag> tags)
		{
			this.member = member;
			this.tags = tags;
		}

		Member getMember()
		{
			return member;
		}

		/** Only the new content that matches what you picked, in display order. */
		List<Tag> getTags()
		{
			return tags;
		}
	}

	private Map<String, Set<Tag>> seen; // null until the first list after connecting
	private final Map<String, Long> lastAlert = new HashMap<>();

	/** Forget everything, for example after logging out. The next list sets a new baseline. */
	synchronized void reset()
	{
		seen = null;
		lastAlert.clear();
	}

	/**
	 * @param members   everyone currently visible, as sent by the server
	 * @param mine      the content you picked
	 * @param serverNow the server's clock, used to ignore people whose time has run out
	 * @param now       local clock, used for the cooldown
	 */
	synchronized List<Alert> update(List<Member> members, Set<Tag> mine, long serverNow, long now)
	{
		Map<String, Set<Tag>> current = new HashMap<>();
		for (Member m : members)
		{
			if (m.getExpiresAt() != 0 && m.getExpiresAt() <= serverNow)
			{
				continue;
			}
			Set<Tag> tags = EnumSet.noneOf(Tag.class);
			tags.addAll(m.getTags());
			current.put(m.getId(), tags);
		}

		List<Alert> alerts = new ArrayList<>();
		if (seen != null && !mine.isEmpty())
		{
			for (Member m : members)
			{
				Set<Tag> open = current.get(m.getId());
				if (open == null)
				{
					continue; // expired
				}
				Set<Tag> before = seen.get(m.getId());
				List<Tag> fresh = new ArrayList<>();
				for (Tag t : Tag.values()) // display order
				{
					if (!open.contains(t) || !mine.contains(t) || (before != null && before.contains(t)))
					{
						continue;
					}
					String key = m.getId() + ":" + t.getId();
					Long last = lastAlert.get(key);
					if (last != null && now - last < COOLDOWN_MS)
					{
						continue;
					}
					lastAlert.put(key, now);
					fresh.add(t);
				}
				if (!fresh.isEmpty())
				{
					alerts.add(new Alert(m, fresh));
				}
			}
		}
		seen = current;
		return alerts;
	}
}
