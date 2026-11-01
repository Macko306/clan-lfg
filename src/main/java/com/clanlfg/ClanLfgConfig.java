package com.clanlfg;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(ClanLfgConfig.GROUP)
public interface ClanLfgConfig extends Config
{
	String GROUP = "clanlfg";

	@ConfigItem(
		keyName = "groupName",
		name = "Group name",
		description = "Your group's name, for example My Clan (capitals and spaces don't matter). You can also enter it in the Clan LFG side panel.",
		position = 1
	)
	default String groupName()
	{
		return "";
	}

	@ConfigItem(
		keyName = "groupPassword",
		name = "Group password",
		description = "The password your group admin gave you. Leave it empty if your group has none.",
		secret = true,
		position = 2
	)
	default String groupPassword()
	{
		return "";
	}

	@ConfigItem(
		keyName = "showOnLogin",
		name = "Show me on login",
		description = "Switch \"Show me to group\" on automatically when you log in. You only appear once you have picked something you are open to, and you can switch it off any time in the panel.",
		position = 3
	)
	default boolean autoShow()
	{
		return true;
	}
}
