package com.clanlfg;

import java.util.Locale;

/**
 * People type a group's name the way they say it: "My Clan", "my-clan" and "myclan" are the
 * same group. This follows the same rules as the server (case, spaces, dashes, underscores, dots and
 * apostrophes are ignored), so a mistake can be caught before asking the server.
 */
final class GroupName
{
	private GroupName()
	{
	}

	/** The group's short code (2 to 16 lowercase letters or digits), or null if this can't be a group name. */
	static String slug(String raw)
	{
		if (raw == null || raw.length() > 64)
		{
			return null;
		}
		String s = raw.toLowerCase(Locale.ROOT).replaceAll("[\\s_.'’-]+", "");
		return s.matches("[a-z0-9]{2,16}") ? s : null;
	}
}
