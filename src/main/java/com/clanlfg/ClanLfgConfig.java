package com.clanlfg;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup(ClanLfgConfig.GROUP)
public interface ClanLfgConfig extends Config
{
	String GROUP = "clanlfg";

	@ConfigSection(
		name = "1. Your group",
		description = "<html>Clan LFG shows who in your clan or friends group is online and open to raids, bosses and more.<br><br>"
			+ "<b>Join</b> your clan's group: enter its name and password here (or in the side panel).<br>"
			+ "<b>Create</b> a group: use the Create new group button in the side panel. You become its owner and can "
			+ "change the password or remove members there.<br><br>"
			+ "Your choices are shared only with members of your group, through the plugin's server.</html>",
		position = 0,
		closedByDefault = false
	)
	String groupSection = "groupSection";

	@ConfigSection(
		name = "2. Then open the side panel",
		description = "<html>Once you're in a group, open the <b>Clan LFG</b> panel from the RuneLite sidebar (the group icon).<br><br>"
			+ "There you pick the content you're open to, add a short message, and keep <b>Show me to group</b> ticked. "
			+ "You only appear once you've picked something, and you can hide yourself at any time.<br><br>"
			+ "The list shows everyone who's open, grouped by content. <b>Alert me in chat</b> tells you when someone "
			+ "opens up for something you picked.</html>",
		position = 10,
		closedByDefault = false
	)
	String panelSection = "panelSection";

	@ConfigItem(
		keyName = "groupName",
		name = "Group name",
		description = "Your group's name, for example My Clan (capitals and spaces don't matter). Changing it reconnects you. To create a new group, use the side panel.",
		section = groupSection,
		position = 1
	)
	default String groupName()
	{
		return "";
	}

	@ConfigItem(
		keyName = "groupPassword",
		name = "Group password",
		description = "The password your group's owner gave you. Leave it empty if your group has none.",
		secret = true,
		section = groupSection,
		position = 2
	)
	default String groupPassword()
	{
		return "";
	}

	@ConfigItem(
		keyName = "showOnLogin",
		name = "Show me on login",
		description = "Switch \"Show me to group\" on automatically when you log in. You only appear once you have picked something you are open to, and you can switch it off any time in the side panel.",
		section = panelSection,
		position = 11
	)
	default boolean autoShow()
	{
		return true;
	}
}
