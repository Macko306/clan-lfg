package com.clanlfg;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/** Starts a development copy of RuneLite with this plugin loaded. Run with `./gradlew run`. */
public class ClanLfgPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(ClanLfgPlugin.class);
		RuneLite.main(args);
	}
}
