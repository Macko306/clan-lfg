package com.clanlfg;

import java.awt.Color;

/**
 * The kinds of content a player can say they are open to. The ids MUST match TAG_IDS in
 * worker/src/validate.ts, because the server rejects anything it does not know.
 * Declaration order is the display order: raids first, then group bosses, and so on.
 * Short labels are for the narrow buttons; full names are used for headings and tooltips.
 */
public enum Tag
{
	// Item ids are only used to show an in-game icon next to the content.
	COX("cox", "CoX", "Chambers of Xeric", Category.RAIDS, new Color(0xC77DFF), 20997), // Twisted bow
	TOB("tob", "ToB", "Theatre of Blood", Category.RAIDS, new Color(0xE05A5A), 22325), // Scythe of Vitur
	TOA("toa", "ToA", "Tombs of Amascut", Category.RAIDS, new Color(0xE0B33A), 27275), // Tumeken's shadow
	TFA("tfa", "TFA", "Fractured Archive", Category.RAIDS, new Color(0x5ACBE0), 34717), // Obligator

	GWD("gwd", "GWD", "God Wars Dungeon", Category.GROUP_BOSSES, new Color(0xE0E0E0), 11802), // Armadyl godsword
	TITANS("titans", "Titans", "Royal Titans (duo)", Category.GROUP_BOSSES, new Color(0x7FB6FF), 30638), // Giantsoul amulet
	NEX("nex", "Nex", "Nex", Category.GROUP_BOSSES, new Color(0xB04A4A), 26235), // Zaryte vambraces
	CORP("corp", "Corp", "Corporeal Beast", Category.GROUP_BOSSES, new Color(0xA0A0C0), 12821), // Spectral spirit shield
	NIGHTMARE("nightmare", "NM", "The Nightmare", Category.GROUP_BOSSES, new Color(0x9B6BD6), 24417), // Inquisitor's mace
	YAMA("yama", "Yama", "Yama (duo)", Category.GROUP_BOSSES, new Color(0xE0763A), 30750), // Oathplate helm
	HUEY("huey", "Huey", "The Hueycoatl", Category.GROUP_BOSSES, new Color(0x6FB5A0), 30070), // Dragon hunter wand
	DKS("dks", "DKs", "Dagannoth Kings", Category.GROUP_BOSSES, new Color(0x8A9BB5), 6737), // Berserker ring
	KQ("kq", "KQ", "Kalphite Queen", Category.GROUP_BOSSES, new Color(0xC0B060), 7981), // KQ head

	CALLISTO("callisto", "Cally", "Callisto / Artio", Category.WILDERNESS, new Color(0xB5803C), 13178), // Callisto cub
	VENENATIS("venenatis", "Veni", "Venenatis / Spindel", Category.WILDERNESS, new Color(0x9B7BC0), 13177), // Venenatis spiderling
	VETION("vetion", "Vetion", "Vet'ion / Calvar'ion", Category.WILDERNESS, new Color(0xC05050), 13179), // Vet'ion Jr.
	SCORPIA("scorpia", "Scorpia", "Scorpia", Category.WILDERNESS, new Color(0xD0A040), 13181), // Scorpia's offspring
	CHAOSELEMENTAL("chaoselemental", "Chaos", "Chaos Elemental", Category.WILDERNESS, new Color(0xE0603C), 7158), // Dragon 2h sword
	REVENANTS("revenants", "Revs", "Revenants", Category.WILDERNESS, new Color(0x70C0A0), 21820), // Revenant ether
	WILDYAGILITY("wildyagility", "Agility", "Wilderness Agility", Category.WILDERNESS, new Color(0x6CC070), 9771), // Agility cape
	PKING("pking", "PKing", "PKing", Category.WILDERNESS, new Color(0xE04040), 4151), // Abyssal whip

	WINTERTODT("wintertodt", "WT", "Wintertodt", Category.SKILLING_CONTENT, new Color(0x7ABCE8), 20714), // Tome of fire
	TEMPOROSS("tempoross", "Tempo", "Tempoross", Category.SKILLING_CONTENT, new Color(0x4C9FD6), 25574), // Tome of water
	ZALCANO("zalcano", "Zalcano", "Zalcano", Category.SKILLING_CONTENT, new Color(0xE0903A), 23908), // Zalcano shard
	GOTR("gotr", "GOTR", "Guardians of the Rift", Category.SKILLING_CONTENT, new Color(0xB07CE0), 26798), // Catalytic talisman
	VOLCANIC("volcanic", "VM", "Volcanic Mine", Category.SKILLING_CONTENT, new Color(0xD0703C), 1275), // Rune pickaxe

	PESTCONTROL("pestcontrol", "PC", "Pest Control", Category.MINIGAMES, new Color(0x9BC070), 8839), // Void knight top
	BARBASSAULT("ba", "BA", "Barbarian Assault", Category.MINIGAMES, new Color(0xC08060), 10548), // Fighter hat
	CASTLEWARS("castlewars", "CW", "Castle Wars", Category.MINIGAMES, new Color(0xD0C060), 11079), // Castle wars bracelet
	SOULWARS("soulwars", "SW", "Soul Wars", Category.MINIGAMES, new Color(0x70B0E0), 25344), // Soul cape
	LMS("lms", "LMS", "Last Man Standing", Category.MINIGAMES, new Color(0xE05A5A), 4587), // Dragon scimitar
	NMZ("nmz", "NMZ", "Nightmare Zone", Category.MINIGAMES, new Color(0xA080D0), 7509), // Dwarven rock cake

	BOSSES("bosses", "Bosses", "Other bosses", Category.OTHER_PVM, new Color(0x5AA0E0), 13652), // Dragon claws
	SLAYER("slayer", "Slayer", "Slayer", Category.OTHER_PVM, new Color(0x6CC070), 11864), // Slayer helmet

	SKILLING("skilling", "Skilling", "Skilling", Category.GENERAL, new Color(0x4CC0B0), 11920), // Dragon pickaxe
	GAMES("games", "Games", "Games (bingo, clan games)", Category.GENERAL, new Color(0xE0A0D0), 4566), // Rubber chicken
	LEARNING("learning", "Learning", "Learning", Category.GENERAL, new Color(0xE08AC0), 1277), // Bronze sword
	TEACHING("teaching", "Teaching", "Teaching", Category.GENERAL, new Color(0x9AA0E0), 11640), // Book of knowledge
	SOCIAL("social", "Social", "Social", Category.GENERAL, new Color(0xAAAAAA), 1044); // Green partyhat

	/** How the content is grouped in the panel. */
	public enum Category
	{
		RAIDS("Raids"),
		GROUP_BOSSES("Group bosses"),
		WILDERNESS("Wilderness"),
		SKILLING_CONTENT("Skilling content"),
		MINIGAMES("Minigames"),
		OTHER_PVM("Other PvM"),
		GENERAL("General");

		private final String label;

		Category(String label)
		{
			this.label = label;
		}

		public String getLabel()
		{
			return label;
		}
	}

	private final String id;
	private final String label;
	private final String fullName;
	private final Category category;
	private final Color color;
	private final int itemId;

	Tag(String id, String label, String fullName, Category category, Color color, int itemId)
	{
		this.id = id;
		this.label = label;
		this.fullName = fullName;
		this.category = category;
		this.color = color;
		this.itemId = itemId;
	}

	public String getId()
	{
		return id;
	}

	/** Short name, used on the buttons. */
	public String getLabel()
	{
		return label;
	}

	/** Full name, used for the list headings and tooltips. */
	public String getFullName()
	{
		return fullName;
	}

	public Category getCategory()
	{
		return category;
	}

	public Color getColor()
	{
		return color;
	}

	public int getItemId()
	{
		return itemId;
	}

	/** Returns the tag with this server id, or null for ids this version does not know about. */
	public static Tag fromId(String id)
	{
		for (Tag t : values())
		{
			if (t.id.equals(id))
			{
				return t;
			}
		}
		return null;
	}
}
