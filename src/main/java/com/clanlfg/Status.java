package com.clanlfg;

/** The short free-text message shown next to a member's tags, e.g. "ToB - taking learners". */
public final class Status
{
	public static final int MAX_LENGTH = 80;

	private Status()
	{
	}

	/**
	 * Makes any text safe to send or show: collapses whitespace, drops control and invisible
	 * characters, and cuts it to {@link #MAX_LENGTH}. Same rules as the server, which is also applied
	 * to text coming back from it. Never returns null.
	 */
	public static String clean(String raw)
	{
		if (raw == null)
		{
			return "";
		}
		StringBuilder out = new StringBuilder(Math.min(raw.length(), MAX_LENGTH));
		boolean pendingSpace = false;
		for (int i = 0; i < raw.length(); )
		{
			int cp = raw.codePointAt(i);
			i += Character.charCount(cp);
			if (Character.isWhitespace(cp) || Character.isSpaceChar(cp))
			{
				pendingSpace = out.length() > 0;
				continue;
			}
			if (!allowed(cp))
			{
				continue;
			}
			int add = (pendingSpace ? 1 : 0) + Character.charCount(cp);
			if (out.length() + add > MAX_LENGTH)
			{
				break;
			}
			if (pendingSpace)
			{
				out.append(' ');
				pendingSpace = false;
			}
			out.appendCodePoint(cp);
		}
		return out.toString();
	}

	/** Letters, marks, numbers, punctuation and symbols. No control, format or private-use characters. */
	private static boolean allowed(int cp)
	{
		switch (Character.getType(cp))
		{
			case Character.UPPERCASE_LETTER:
			case Character.LOWERCASE_LETTER:
			case Character.TITLECASE_LETTER:
			case Character.MODIFIER_LETTER:
			case Character.OTHER_LETTER:
			case Character.NON_SPACING_MARK:
			case Character.ENCLOSING_MARK:
			case Character.COMBINING_SPACING_MARK:
			case Character.DECIMAL_DIGIT_NUMBER:
			case Character.LETTER_NUMBER:
			case Character.OTHER_NUMBER:
			case Character.CONNECTOR_PUNCTUATION:
			case Character.DASH_PUNCTUATION:
			case Character.START_PUNCTUATION:
			case Character.END_PUNCTUATION:
			case Character.INITIAL_QUOTE_PUNCTUATION:
			case Character.FINAL_QUOTE_PUNCTUATION:
			case Character.OTHER_PUNCTUATION:
			case Character.MATH_SYMBOL:
			case Character.CURRENCY_SYMBOL:
			case Character.MODIFIER_SYMBOL:
			case Character.OTHER_SYMBOL:
				return true;
			default:
				return false;
		}
	}
}
