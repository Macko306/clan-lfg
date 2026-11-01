package com.clanlfg;

/** How long you stay visible after switching "Show me to group" on. Minutes must match the server's allowed list. */
public enum VisibleFor
{
	HOUR_1("1 hour", 60),
	HOURS_2("2 hours", 120),
	HOURS_4("4 hours", 240),
	UNTIL_LOGOUT("Until logout", 0);

	private final String label;
	private final int minutes;

	VisibleFor(String label, int minutes)
	{
		this.label = label;
		this.minutes = minutes;
	}

	public int getMinutes()
	{
		return minutes;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
