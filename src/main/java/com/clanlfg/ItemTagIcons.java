package com.clanlfg;

import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.function.Consumer;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.SwingUtilities;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.AsyncBufferedImage;

/** Shows real in-game item pictures (the Scythe for ToB and so on) using RuneLite's item images. */
final class ItemTagIcons implements TagIcons
{
	private static final int HEIGHT = 22;

	private final ItemManager itemManager;

	ItemTagIcons(ItemManager itemManager)
	{
		this.itemManager = itemManager;
	}

	@Override
	public void load(Tag tag, Consumer<Icon> sink)
	{
		AsyncBufferedImage image = itemManager.getImage(tag.getItemId());
		Runnable apply = () ->
		{
			Icon icon = scaled(image);
			SwingUtilities.invokeLater(() -> sink.accept(icon));
		};
		image.onLoaded(apply); // item pictures arrive a moment later; draw again when they do
		apply.run();
	}

	private static Icon scaled(BufferedImage src)
	{
		int w = Math.max(1, Math.round(src.getWidth() * (float) HEIGHT / src.getHeight()));
		BufferedImage out = new BufferedImage(w, HEIGHT, BufferedImage.TYPE_INT_ARGB);
		java.awt.Graphics2D g = out.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.drawImage(src, 0, 0, w, HEIGHT, null);
		g.dispose();
		return new ImageIcon(out);
	}
}
