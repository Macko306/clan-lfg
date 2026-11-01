# Clan LFG

A RuneLite plugin for clans and friends groups. It's intended to make grouping easier. You pick the content you're up for, add a short message if you
like, and everyone in your group can see who is online and what content they want to send.

## What it does

- **Pick what you're open to.** Content is grouped: Raids (CoX, ToB, ToA, TFA), Group bosses (GWD, Royal Titans,
  Nex, Corp, Nightmare, Yama, Hueycoatl, Dagannoth Kings, Kalphite Queen), Wilderness (each boss, Wilderness
  Agility, Revenants, PKing), Skilling content (Wintertodt, Tempoross, Zalcano, Guardians of the Rift, Volcanic
  Mine), Minigames, Other PvM and General (Skilling, Games, Learning, Teaching, Social). 
- **Add a message.** Up to 80 characters, shown under your name, like "ToB - taking learners" or "AFKing - pm me and ill join".
- **See who else is open.** The list is grouped by content, so you can find everyone open to ToB in one place.
  Tick **Only my picks** to see just the content you picked, or use **Filter** to narrow it further.
- **Get told when someone opens up.** With **Alert me in chat** on, a line appears in your own chat box when
  someone newly opens up for something you picked. Only you see it.
- **Choose how long you're visible:** 1 hour, 2 hours, 4 hours or until you log out.

You only appear to your group once you've picked at least one thing, and you drop off the list as soon as you log
out. You can switch **Show me to group** off at any time.

## Getting started

1. Open the Clan LFG panel from the RuneLite sidebar (the two-people icon).
2. If your clan already has a group, type its **name** and **password** and press **Join group**. If not, type the
   name you want, add a password and press **Create new group** (see [Creating a group](#creating-a-group)).
   The group name isn't case sensitive and spaces don't matter, so "My Clan" and "myclan" are the same group.
3. Pick what you're open to, add a message if you like, and make sure **Show me to group** is ticked.

The group name, password and **Show me on login** can also be changed in the plugin's settings.

## How it works

The plugin connects to a small server that keeps one room per group. When you pick content or change your
message, the plugin tells the server, and the server passes it on to everyone else in the same group who is
online. When you log out or close RuneLite, you're removed from the list.

The server is a Cloudflare Worker. Its code is open and lives in a separate repository,
[clan-lfg-server](https://github.com/Macko306/clan-lfg-server), so you can read exactly what it does or run your
own copy for your group.

## Privacy

Because this plugin talks to an outside server, here is exactly what is sent.

| What | When | Who can see it |
|---|---|---|
| Your player name | when you join a group | members of that group |
| The content you picked, your world, your message and how long you'll be visible | only while **Show me to group** is on | members of that group |
| A random token, kept in your RuneLite config | with every connection | only you and the server |

What the server keeps: your player name, a hashed copy of your token and the time you were last connected. For each
group it keeps the group's name, a hashed password (if it has one) and who the owner is. What you picked, your
message and whether you're online exist only while you are connected. They are never written to the server's
database.

- Nothing is sent until you join or create a group.
- The server doesn't log requests, and uses addresses only briefly in memory to limit abuse. When someone creates
  a group, a hashed copy of their address is kept for 24 hours, only to limit how many groups one person can make.
- **Leave group** (at the bottom of the panel) deletes your data from the server straight away.
- People who haven't connected for 90 days are removed automatically.
- No chat, inventory, bank, location or any other game data is read or sent.

Anyone in your group can see your player name, world and what you picked while you're visible. That's the point
of the plugin, so only join groups you trust.

## What it doesn't do

The plugin never sends chat messages, clicks, or does anything in the game for you. It doesn't join RuneLite
parties or invite anyone. The chat alerts are lines displayed on your own screen, and nothing is sent to other
players. The server address is built into the plugin and can't be changed from a setting.

## Creating a group

Anyone can create a group from the plugin, and there is no charge. Groups live on a shared server that I run.

1. In the panel, type a group name, choose a password and press **Create new group**. Names are first come, first
   served. You become the group's **owner**.
2. Share the name and password with your clan, privately.
3. As the owner you get extra controls in the panel:
   - **Change password** sets a new one. People already in the group stay in, and anyone who only had the old
     password can no longer join.
   - **Remove password** makes the group open to anyone who knows its name.
   - **Right-click a member** in the list and choose **Remove from group**. They can't rejoin under that name.
     If they could just join again with another name, change the password too.
4. If the owner leaves, the longest-standing member becomes the owner. When the last member leaves, the group is
   deleted and its name can be used again.

A password is optional, but without one anyone who knows or guesses the group's name can join and see who is open
to what, so use one for a clan. To keep the server affordable, one person can create three groups a day and the
server holds a limited number of groups in total. If this ever becomes too costly to run, creating new groups may
be switched off for a while. Groups that already exist keep working.

If you'd rather not depend on my server, the [server repository](https://github.com/Macko306/clan-lfg-server)
explains how to run your own on a free Cloudflare account. Be aware that the plugin from the Plugin Hub always
talks to my server, so your own server would need a build of the plugin pointed at it.

## Support

Happy to continue development, let me know if you find any issues or have improvements/suggestions. Clan LFG is an independent plugin and isn't affiliated with Jagex or RuneLite.

## License

BSD 2-Clause. See [LICENSE](LICENSE).
