package com.clanlfg;

import java.util.function.Consumer;
import javax.swing.Icon;

/**
 * Supplies the small picture shown next to each kind of content. Kept separate from the panel so
 * the panel has no RuneLite dependency for this. An icon may arrive later (item images load in the
 * background), so the sink can be called more than once. Calls to the sink happen on the Swing thread.
 */
public interface TagIcons
{
	void load(Tag tag, Consumer<Icon> sink);
}
