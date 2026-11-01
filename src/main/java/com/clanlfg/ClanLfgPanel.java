package com.clanlfg;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.FontMetrics;
import java.awt.GridLayout;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.basic.BasicHTML;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
import javax.swing.text.View;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

/**
 * The side panel. Layout, top to bottom:
 *   status line
 *   "I'm open to"  (content buttons, grouped: raids, group bosses, ...)
 *   optional message, how long, and the "Show me to group" switch
 *   "Who's open": group members, grouped under the content they are open to (with an optional filter)
 * A separate "join" screen (group name and password) is shown until a group has been joined.
 *
 * All public methods are safe to call from any thread.
 */
public class ClanLfgPanel extends PluginPanel
{
	public interface Callbacks
	{
		void onTagsChanged(Set<Tag> tags);

		void onVisibleChanged(boolean visible);

		void onDurationChanged(VisibleFor duration);

		void onStatusChanged(String status);

		void onCollapsedChanged(Set<Tag.Category> collapsed);

		void onAlertsChanged(boolean enabled);

		void onJoin(String groupName, String password);

		void onLeaveGroup();
	}

	private static final String CARD_JOIN = "join";
	private static final String CARD_MAIN = "main";
	private static final String MESSAGE_HINT = "Im AFKing but @ or pm me, id be down";
	private static final Color ORANGE = ColorScheme.BRAND_ORANGE;
	private static final Color ORANGE_BG = new Color(0x33, 0x27, 0x0F);
	private static final Color GREEN = new Color(0x4C, 0xAF, 0x50);
	private static final Color AMBER = new Color(0xE0, 0xB3, 0x3A);
	private static final Color RED = new Color(0xE0, 0x5A, 0x5A);
	private static final Color GRAY = new Color(0x77, 0x77, 0x77);
	private static final Color MUTED = ColorScheme.LIGHT_GRAY_COLOR;
	/** Usable width inside the panel: 225 minus the 6px border on each side. */
	private static final int CONTENT_WIDTH = 205;
	private static final int ROW_TEXT_WIDTH = 188;
	/** A section lists this many people before "Show more", so a huge group cannot bog the panel down. */
	static final int SECTION_ROW_LIMIT = 25;
	private static final int RENDER_WINDOW_MS = 250;

	/**
	 * Text sizes follow RuneLite's own default font (the same size other plugins use), instead of
	 * a size we pick ourselves, which came out smaller than the rest of the client.
	 */
	private static float textSize()
	{
		java.awt.Font f = javax.swing.UIManager.getFont("Label.font");
		return f != null ? f.getSize2D() : 14f;
	}

	private static float smallSize()
	{
		return textSize();
	}

	private final Callbacks callbacks;
	private final TagIcons icons;
	private final CardLayout cards = new CardLayout();
	private final JPanel cardHost = new JPanel(cards);
	private final Timer refreshTimer;
	private Timer renderTimer;
	private boolean renderDirty;
	/** Content types whose full list is open (every other section shows only its first rows). */
	private final Set<Tag> showAll = EnumSet.noneOf(Tag.class);

	// join screen
	private final JTextField nameField = new JTextField();
	private final JPasswordField passwordField = new JPasswordField();
	private final JLabel joinMessage = new JLabel(" ");

	// main screen
	private final JLabel statusLabel = new JLabel();
	private final JPanel myChips = column();
	private final JPanel filterChips = new JPanel(new GridLayout(0, 5, 3, 3));
	private final JPanel filterHost = wrapLeft(filterChips);
	private final JTextArea messageField = new JTextArea(3, 1)
	{
		@Override
		protected void paintComponent(java.awt.Graphics g)
		{
			super.paintComponent(g);
			if (getText().isEmpty() && !isFocusOwner())
			{
				java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
				g2.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
					java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
				g2.setColor(GRAY);
				g2.setFont(getFont().deriveFont(java.awt.Font.ITALIC));
				FontMetrics fm = g2.getFontMetrics();
				java.awt.Insets in = getInsets();
				int maxW = getWidth() - in.left - in.right;
				int y = in.top + fm.getAscent();
				StringBuilder line = new StringBuilder();
				for (String word : MESSAGE_HINT.split(" "))
				{
					String tryLine = line.length() == 0 ? word : line + " " + word;
					if (line.length() > 0 && fm.stringWidth(tryLine) > maxW)
					{
						g2.drawString(line.toString(), in.left, y);
						y += fm.getHeight();
						line = new StringBuilder(word);
					}
					else
					{
						line = new StringBuilder(tryLine);
					}
				}
				g2.drawString(line.toString(), in.left, y);
				g2.dispose();
			}
		}
	};
	private String sentStatus = "";
	private final JComboBox<VisibleFor> durationBox = new JComboBox<>(VisibleFor.values());
	private final JCheckBox visibleBox = new JCheckBox("Show me to group");
	private final JLabel visibleHint = new JLabel(" ");
	private final JCheckBox onlyMine = new JCheckBox("Only my picks");
	private final JCheckBox alertBox = new JCheckBox("Alert me in chat");
	private final JLabel clearFilter = new JLabel("Clear");
	private final JLabel filterToggle = new JLabel("Filter ▾");
	private final JPanel listPanel = new JPanel();
	private final JLabel footer = new JLabel(" ");
	private final JButton leaveButton = new JButton("Leave group");

	// state (touched only on the Swing thread)
	private final Set<Tag> mine = EnumSet.noneOf(Tag.class);
	private final Set<Tag> filter = EnumSet.noneOf(Tag.class);
	private final Set<Tag.Category> expanded = EnumSet.noneOf(Tag.Category.class);
	private List<Member> members = new ArrayList<>();
	private long clockOffsetMs;
	private LfgSession.State state = LfgSession.State.IDLE;
	private String groupName = "";
	private boolean updatingUi;

	public ClanLfgPanel(Callbacks callbacks, Set<Tag> initialTags, VisibleFor initialDuration, String initialStatus,
		Set<Tag.Category> initialCollapsed, boolean initialAlerts, TagIcons icons)
	{
		super();
		this.callbacks = callbacks;
		this.icons = icons;
		alertBox.setSelected(initialAlerts);
		mine.addAll(initialTags);
		// Everything starts open; people close the groups they do not use and we remember that.
		expanded.addAll(EnumSet.allOf(Tag.Category.class));
		expanded.removeAll(initialCollapsed);
		sentStatus = Status.clean(initialStatus);
		messageField.setText(sentStatus);

		cardHost.setOpaque(false);
		cardHost.add(buildJoinCard(), CARD_JOIN);
		cardHost.add(buildMainCard(initialDuration), CARD_MAIN);
		add(cardHost);
		cards.show(cardHost, CARD_MAIN);
		render();

		// Keeps "1h 20m left" fresh and drops people whose time ran out.
		refreshTimer = new Timer(30_000, e -> renderList());
		refreshTimer.start();

		renderTimer = new Timer(RENDER_WINDOW_MS, e ->
		{
			if (renderDirty)
			{
				renderDirty = false;
				renderList();
				renderTimer.restart(); // keep the pace down while updates keep coming
			}
		});
		renderTimer.setRepeats(false);
	}

	/** Call when the plugin shuts down. */
	public void dispose()
	{
		refreshTimer.stop();
		renderTimer.stop();
	}

	// ------------------------------------------------------------------ public updates

	public void setState(LfgSession.State newState, String message)
	{
		onEdt(() ->
		{
			state = newState;
			setWrappedText(joinMessage, message == null ? "" : esc(message), CONTENT_WIDTH);
			groupName = newState == LfgSession.State.CONNECTED && message != null ? message : groupName;
			render();
		});
	}

	public void setMembers(List<Member> list, long offsetMs)
	{
		final List<Member> copy = new ArrayList<>(list);
		onEdt(() ->
		{
			members = copy;
			clockOffsetMs = offsetMs;
			scheduleRender();
		});
	}

	/**
	 * In a big group the server can send many updates a second, and redrawing the whole list for each
	 * one makes the panel stutter. The first update is drawn straight away; any that arrive within the
	 * next quarter second are folded into a single redraw.
	 */
	private void scheduleRender()
	{
		if (renderTimer.isRunning())
		{
			renderDirty = true;
		}
		else
		{
			renderList();
			renderTimer.start();
		}
	}

	/** Used when the "visible for" timer runs out. Does not fire the callback. */
	public void setVisibleChecked(boolean visible)
	{
		onEdt(() ->
		{
			updatingUi = true;
			visibleBox.setSelected(visible);
			updatingUi = false;
			renderHint();
		});
	}

	/** Used when auto-show is on. */
	public void refreshVisibleHint()
	{
		onEdt(this::renderHint);
	}

	// ------------------------------------------------------------------ building the screens

	private JPanel buildJoinCard()
	{
		JPanel p = column();
		p.add(heading("Clan LFG"));
		p.add(gap(6));
		p.add(note("Type your group's name and the password from your clan admin to see who is online and open to group content."));
		p.add(gap(8));
		p.add(small("Group name"));
		nameField.setFont(nameField.getFont().deriveFont(textSize()));
		nameField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
		nameField.setAlignmentX(LEFT_ALIGNMENT);
		nameField.setToolTipText("For example: My Clan (capitals and spaces don't matter)");
		p.add(nameField);
		p.add(gap(6));
		p.add(small("Password (if there is one)"));
		for (javax.swing.text.JTextComponent f : new javax.swing.text.JTextComponent[] {nameField, passwordField})
		{
			f.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			f.setForeground(ColorScheme.TEXT_COLOR);
			f.setCaretColor(Color.WHITE);
		}
		passwordField.setFont(passwordField.getFont().deriveFont(textSize()));
		passwordField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
		passwordField.setAlignmentX(LEFT_ALIGNMENT);
		p.add(passwordField);
		p.add(gap(6));
		JButton join = new JButton("Join group");
		join.setFont(join.getFont().deriveFont(textSize()));
		join.setAlignmentX(LEFT_ALIGNMENT);
		join.addActionListener(e ->
		{
			String name = nameField.getText().trim();
			if (name.isEmpty())
			{
				setWrappedText(joinMessage, "Type your group's name first.", CONTENT_WIDTH);
				return;
			}
			if (GroupName.slug(name) == null)
			{
				setWrappedText(joinMessage, "That doesn't look like a group name. Type it the way your clan calls it, for example My Clan.", CONTENT_WIDTH);
				return;
			}
			joinMessage.setText(" ");
			callbacks.onJoin(name, new String(passwordField.getPassword()));
		});
		nameField.addActionListener(e -> passwordField.requestFocusInWindow());
		passwordField.addActionListener(e -> join.doClick());
		p.add(join);
		p.add(gap(6));
		joinMessage.setFont(joinMessage.getFont().deriveFont(textSize()));
		joinMessage.setAlignmentX(LEFT_ALIGNMENT);
		joinMessage.setForeground(AMBER);
		p.add(joinMessage);
		p.add(gap(10));
		p.add(note("Only your player name, the content you pick and your optional message are shared, and only with members of your group. Nothing is shared until you pick something, and you can switch \"Show me to group\" off at any time."));
		return p;
	}

	private JPanel buildMainCard(VisibleFor initialDuration)
	{
		JPanel p = column();

		statusLabel.setFont(statusLabel.getFont().deriveFont(textSize()));
		statusLabel.setAlignmentX(LEFT_ALIGNMENT);
		p.add(heading("Clan LFG"));
		p.add(gap(2));
		p.add(statusLabel);
		p.add(gap(10));

		p.add(small("I'm open to"));
		p.add(gap(4));
		fillChipSections(myChips, mine, () ->
		{
			EnumSet<Tag> copy = EnumSet.noneOf(Tag.class);
			copy.addAll(mine);
			renderHint();
			if (onlyMine.isSelected())
			{
				renderList(); // the list follows your picks while "Only my picks" is on
			}
			callbacks.onTagsChanged(copy);
		});
		p.add(wrapLeft(myChips));
		p.add(gap(8));

		p.add(small("Message (optional)"));
		p.add(gap(4));
		buildMessageBox();
		p.add(messageField);
		p.add(gap(8));

		JPanel durationRow = new JPanel(new BorderLayout(6, 0));
		durationRow.setOpaque(false);
		durationRow.add(small("Visible for"), BorderLayout.WEST);
		durationBox.setFont(durationBox.getFont().deriveFont(smallSize()));
		durationBox.setSelectedItem(initialDuration);
		durationBox.addActionListener(e ->
		{
			if (!updatingUi && durationBox.getSelectedItem() != null)
			{
				callbacks.onDurationChanged((VisibleFor) durationBox.getSelectedItem());
			}
		});
		durationRow.add(durationBox, BorderLayout.EAST);
		durationRow.setAlignmentX(LEFT_ALIGNMENT);
		durationRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
		p.add(durationRow);
		p.add(gap(4));

		visibleBox.setFont(visibleBox.getFont().deriveFont(textSize()));
		visibleBox.setOpaque(false);
		visibleBox.setAlignmentX(LEFT_ALIGNMENT);
		visibleBox.addActionListener(e ->
		{
			if (!updatingUi)
			{
				callbacks.onVisibleChanged(visibleBox.isSelected());
				renderHint();
			}
		});
		p.add(visibleBox);
		visibleHint.setFont(visibleHint.getFont().deriveFont(smallSize()));
		visibleHint.setAlignmentX(LEFT_ALIGNMENT);
		visibleHint.setBorder(new EmptyBorder(0, 0, 0, 10));
		visibleHint.setForeground(MUTED);
		p.add(visibleHint);
		p.add(gap(12));

		JPanel findRow = new JPanel(new BorderLayout());
		findRow.setOpaque(false);
		JLabel whoLabel = small("Who's open");
		whoLabel.setFont(whoLabel.getFont().deriveFont(java.awt.Font.BOLD, textSize()));
		whoLabel.setForeground(Color.WHITE);
		findRow.add(whoLabel, BorderLayout.WEST);
		JPanel links = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
		links.setOpaque(false);
		styleLink(clearFilter);
		clearFilter.setVisible(false);
		clearFilter.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mouseClicked(java.awt.event.MouseEvent e)
			{
				filter.clear();
				fillFilterChips();
				renderList();
			}
		});
		styleLink(filterToggle);
		filterToggle.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mouseClicked(java.awt.event.MouseEvent e)
			{
				boolean open = !filterHost.isVisible();
				filterHost.setVisible(open);
				filterToggle.setText(open ? "Filter ▴" : "Filter ▾");
				revalidate();
				repaint();
			}
		});
		links.add(clearFilter);
		links.add(filterToggle);
		findRow.add(links, BorderLayout.EAST);
		findRow.setAlignmentX(LEFT_ALIGNMENT);
		findRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
		p.add(findRow);
		p.add(gap(2));

		onlyMine.setFont(onlyMine.getFont().deriveFont(smallSize()));
		onlyMine.setOpaque(false);
		onlyMine.setAlignmentX(LEFT_ALIGNMENT);
		onlyMine.setToolTipText("Only list people open to the same content you picked above");
		onlyMine.addActionListener(e -> renderList());
		p.add(onlyMine);

		alertBox.setFont(alertBox.getFont().deriveFont(smallSize()));
		alertBox.setOpaque(false);
		alertBox.setAlignmentX(LEFT_ALIGNMENT);
		alertBox.setToolTipText("Show a line in your own chat box when someone opens up for something you picked. Only you see it.");
		alertBox.addActionListener(e -> callbacks.onAlertsChanged(alertBox.isSelected()));
		p.add(alertBox);
		p.add(gap(4));

		filterHost.setVisible(false);
		fillFilterChips();
		p.add(filterHost);
		p.add(gap(4));

		listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
		listPanel.setOpaque(false);
		listPanel.setAlignmentX(LEFT_ALIGNMENT);
		p.add(listPanel);
		p.add(gap(6));

		footer.setFont(footer.getFont().deriveFont(smallSize()));
		footer.setBorder(new EmptyBorder(0, 0, 0, 10));
		footer.setForeground(MUTED);
		footer.setAlignmentX(LEFT_ALIGNMENT);
		p.add(footer);
		p.add(gap(6));
		leaveButton.setFont(leaveButton.getFont().deriveFont(textSize()));
		leaveButton.setAlignmentX(LEFT_ALIGNMENT);
		leaveButton.addActionListener(e ->
		{
			int answer = JOptionPane.showConfirmDialog(this,
				"Leave this group and delete your data from the server?\nYou can join again later with the group's name and password.",
				"Leave group", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
			if (answer == JOptionPane.OK_OPTION)
			{
				callbacks.onLeaveGroup();
			}
		});
		p.add(leaveButton);
		return p;
	}

	private void buildMessageBox()
	{
		messageField.setLineWrap(true);
		messageField.setWrapStyleWord(true);
		messageField.setFont(messageField.getFont().deriveFont(textSize()));
		messageField.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		messageField.setForeground(ColorScheme.TEXT_COLOR);
		messageField.setCaretColor(Color.WHITE);
		messageField.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
			new EmptyBorder(4, 6, 4, 6)));
		((AbstractDocument) messageField.getDocument()).setDocumentFilter(new DocumentFilter()
		{
			@Override
			public void insertString(FilterBypass fb, int offset, String text, AttributeSet attrs) throws BadLocationException
			{
				replace(fb, offset, 0, text, attrs);
			}

			@Override
			public void replace(FilterBypass fb, int offset, int length, String text, AttributeSet attrs) throws BadLocationException
			{
				String incoming = text == null ? "" : text.replaceAll("[\\p{Cntrl}]", " ");
				int room = Status.MAX_LENGTH - (fb.getDocument().getLength() - length);
				if (room <= 0 && !incoming.isEmpty())
				{
					return;
				}
				if (incoming.length() > room)
				{
					incoming = incoming.substring(0, Math.max(0, room));
				}
				super.replace(fb, offset, length, incoming, attrs);
			}
		});
		// Enter saves the message (instead of adding a new line).
		messageField.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "commit");
		messageField.getActionMap().put("commit", new AbstractAction()
		{
			@Override
			public void actionPerformed(ActionEvent e)
			{
				commitMessage();
				messageField.transferFocus();
			}
		});
		messageField.setToolTipText("Shown to your group next to your name, e.g. \"ToB - taking learners\". Up to " + Status.MAX_LENGTH + " characters. Press Enter to save.");
		messageField.setAlignmentX(LEFT_ALIGNMENT);
		messageField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 64));
		messageField.addFocusListener(new java.awt.event.FocusAdapter()
		{
			@Override
			public void focusLost(java.awt.event.FocusEvent e)
			{
				commitMessage();
				messageField.repaint();
			}

			@Override
			public void focusGained(java.awt.event.FocusEvent e)
			{
				messageField.repaint();
			}
		});
	}

	/** Sends the message when Enter is pressed or the box loses focus, but only if it changed. */
	private void commitMessage()
	{
		String cleaned = Status.clean(messageField.getText());
		if (!cleaned.equals(messageField.getText()))
		{
			messageField.setText(cleaned);
		}
		if (!cleaned.equals(sentStatus))
		{
			sentStatus = cleaned;
			callbacks.onStatusChanged(cleaned);
		}
	}

	/** The "I'm open to" buttons, one small group per category (Raids, Group bosses, ...). */
	private void fillChipSections(JPanel host, Set<Tag> selection, Runnable onChange)
	{
		host.removeAll();
		for (Tag.Category category : Tag.Category.values())
		{
			JLabel caption = small("");
			caption.setFont(caption.getFont().deriveFont(java.awt.Font.BOLD, smallSize()));
			caption.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
			caption.setToolTipText("Click to show or hide");
			host.add(caption);
			host.add(gap(3));

			JPanel grid = new JPanel(new GridLayout(0, 2, 4, 4));
			grid.setOpaque(false);
			for (Tag tag : Tag.values())
			{
				if (tag.getCategory() == category)
				{
					grid.add(chip(tag, selection, () ->
					{
						updateCaption(category, caption, selection);
						onChange.run();
					}, false));
				}
			}
			grid.setAlignmentX(LEFT_ALIGNMENT);
			JPanel gridHost = wrapLeft(grid);
			gridHost.setVisible(expanded.contains(category));
			host.add(gridHost);
			host.add(gap(7));

			updateCaption(category, caption, selection);
			caption.addMouseListener(new java.awt.event.MouseAdapter()
			{
				@Override
				public void mouseClicked(java.awt.event.MouseEvent e)
				{
					if (!expanded.remove(category))
					{
						expanded.add(category);
					}
					gridHost.setVisible(expanded.contains(category));
					updateCaption(category, caption, selection);
					Set<Tag.Category> collapsed = EnumSet.allOf(Tag.Category.class);
					collapsed.removeAll(expanded);
					callbacks.onCollapsedChanged(collapsed); // remembered, so it stays how you left it
					host.revalidate();
					ClanLfgPanel.this.revalidate();
					ClanLfgPanel.this.repaint();
				}
			});
		}
		host.revalidate();
		host.repaint();
	}

	/** "▾ RAIDS" when open, "▸ RAIDS · 2 picked" when closed with something ticked inside. */
	private void updateCaption(Tag.Category category, JLabel caption, Set<Tag> selection)
	{
		int picked = 0;
		for (Tag t : selection)
		{
			if (t.getCategory() == category)
			{
				picked++;
			}
		}
		boolean open = expanded.contains(category);
		caption.setText((open ? "▾ " : "▸ ") + category.getLabel().toUpperCase(Locale.ROOT)
			+ (picked > 0 ? "  ·  " + picked + " picked" : ""));
		caption.setForeground(picked > 0 ? ORANGE : MUTED);
	}

	/** The compact filter: just the pictures, with the name in a tooltip. */
	private void fillFilterChips()
	{
		filterChips.removeAll();
		filterChips.setOpaque(false);
		for (Tag tag : Tag.values())
		{
			filterChips.add(chip(tag, filter, this::renderList, true));
		}
		filterChips.revalidate();
		filterChips.repaint();
	}

	private JToggleButton chip(Tag tag, Set<Tag> selection, Runnable onChange, boolean iconOnly)
	{
		JToggleButton b = new JToggleButton();
		b.setFocusPainted(false);
		b.setContentAreaFilled(false);
		b.setOpaque(true);
		b.setMargin(new java.awt.Insets(3, 2, 3, 2));
		b.setFont(b.getFont().deriveFont(smallSize()));
		b.setIconTextGap(6);
		b.setHorizontalAlignment(iconOnly ? JToggleButton.CENTER : JToggleButton.LEFT);
		b.setToolTipText(tag.getFullName());
		b.setSelected(selection.contains(tag));
		styleChip(b, tag, iconOnly);
		icons.load(tag, b::setIcon);
		b.addActionListener(e ->
		{
			if (b.isSelected())
			{
				selection.add(tag);
			}
			else
			{
				selection.remove(tag);
			}
			styleChip(b, tag, iconOnly);
			onChange.run();
		});
		return b;
	}

	private static void styleChip(JToggleButton b, Tag tag, boolean iconOnly)
	{
		boolean on = b.isSelected();
		b.setText(iconOnly ? "" : tag.getLabel());
		b.setBackground(on ? ORANGE_BG : ColorScheme.DARKER_GRAY_COLOR);
		b.setForeground(on ? ORANGE : MUTED);
		b.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(on ? ORANGE : ColorScheme.MEDIUM_GRAY_COLOR),
			new EmptyBorder(2, 4, 2, 3)));
	}

	private static void styleLink(JLabel l)
	{
		l.setForeground(ORANGE);
		l.setFont(l.getFont().deriveFont(smallSize()));
		l.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
	}

	// ------------------------------------------------------------------ rendering

	private void render()
	{
		boolean needsCode = state == LfgSession.State.NEEDS_CODE
			|| state == LfgSession.State.BAD_CODE
			|| state == LfgSession.State.NAME_TAKEN
			|| state == LfgSession.State.REMOVED;
		cards.show(cardHost, needsCode ? CARD_JOIN : CARD_MAIN);

		Color dot;
		String text;
		switch (state)
		{
			case CONNECTED:
				dot = GREEN;
				text = groupName.isEmpty() ? "Connected" : "Connected · " + groupName;
				break;
			case JOINING:
			case CONNECTING:
				dot = AMBER;
				text = "Connecting…";
				break;
			case PENDING:
				dot = AMBER;
				text = "Waiting for admin approval";
				break;
			case OFFLINE:
				dot = AMBER;
				text = "Offline, retrying…";
				break;
			case REPLACED:
				dot = AMBER;
				text = "Connected from another client";
				break;
			case MISCONFIGURED:
				dot = RED;
				text = "Server address not set";
				break;
			case OUTDATED:
				dot = RED;
				text = "Plugin update needed";
				break;
			default:
				dot = GRAY;
				text = "Log in to connect";
				break;
		}
		statusLabel.setText("<html><font color='" + hex(dot) + "'>●</font>&nbsp;" + esc(text) + "</html>");
		statusLabel.setForeground(MUTED);
		leaveButton.setVisible(state == LfgSession.State.CONNECTED || state == LfgSession.State.PENDING);
		renderHint();
		renderList();
	}

	private void renderHint()
	{
		if (visibleBox.isSelected())
		{
			visibleHint.setText(mine.isEmpty() ? "Pick at least one to appear." : "Group members can see you.");
		}
		else
		{
			visibleHint.setText("Hidden. Nobody can see you.");
		}
	}

	private void renderList()
	{
		clearFilter.setVisible(!filter.isEmpty());
		listPanel.removeAll();

		final long serverNow = System.currentTimeMillis() + clockOffsetMs;

		// Which content to list: your own picks (if that box is ticked), narrowed by the filter pictures.
		boolean restricting = onlyMine.isSelected() || !filter.isEmpty();
		Set<Tag> active = EnumSet.noneOf(Tag.class);
		if (onlyMine.isSelected())
		{
			active.addAll(mine);
			if (!filter.isEmpty())
			{
				active.retainAll(filter);
			}
		}
		else
		{
			active.addAll(filter);
		}

		Map<Tag, List<Member>> byTag = new EnumMap<>(Tag.class);
		Set<String> shownIds = new HashSet<>();
		for (Member m : members)
		{
			if (m.getExpiresAt() != 0 && m.getExpiresAt() <= serverNow)
			{
				continue;
			}
			for (Tag t : m.getTags())
			{
				if (!restricting || active.contains(t))
				{
					byTag.computeIfAbsent(t, k -> new ArrayList<>()).add(m);
					shownIds.add(m.getId());
				}
			}
		}

		if (byTag.isEmpty())
		{
			JLabel empty = new JLabel();
			empty.setFont(empty.getFont().deriveFont(textSize()));
			empty.setForeground(MUTED);
			String emptyText;
			if (state != LfgSession.State.CONNECTED)
			{
				emptyText = "Nobody to show yet.";
			}
			else if (onlyMine.isSelected() && mine.isEmpty())
			{
				emptyText = "Pick what you're open to above first.";
			}
			else
			{
				emptyText = restricting ? "Nobody open for that right now." : "Nobody is open right now.";
			}
			setWrappedText(empty, emptyText, CONTENT_WIDTH);
			empty.setAlignmentX(LEFT_ALIGNMENT);
			listPanel.add(empty);
		}

		// One section per kind of content, in the same order as the buttons. Someone open to
		// several things appears under each of them.
		for (Tag tag : Tag.values())
		{
			List<Member> inTag = byTag.get(tag);
			if (inTag == null)
			{
				continue;
			}
			inTag.sort(Comparator.comparing(m -> m.getRsn().toLowerCase(Locale.ROOT)));
			listPanel.add(buildSectionHeader(tag, inTag.size()));
			boolean open = showAll.contains(tag);
			int limit = open ? inTag.size() : Math.min(inTag.size(), SECTION_ROW_LIMIT);
			boolean alt = false;
			for (Member m : inTag.subList(0, limit))
			{
				listPanel.add(buildRow(m, serverNow, alt));
				alt = !alt;
			}
			if (inTag.size() > SECTION_ROW_LIMIT)
			{
				listPanel.add(buildMoreLink(tag, inTag.size() - SECTION_ROW_LIMIT, open));
			}
			listPanel.add(gap(6));
		}
		footer.setText(state == LfgSession.State.CONNECTED
			? shownIds.size() + " of " + members.size() + " open members shown" : " ");
		listPanel.revalidate();
		listPanel.repaint();
	}

	/** "Show 40 more" / "Show fewer" under a long section. */
	private JLabel buildMoreLink(Tag tag, int hidden, boolean open)
	{
		JLabel link = new JLabel(open ? "Show fewer" : "Show " + hidden + " more");
		styleLink(link);
		link.setBorder(new EmptyBorder(4, 6, 2, 0));
		link.setAlignmentX(LEFT_ALIGNMENT);
		link.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mouseClicked(java.awt.event.MouseEvent e)
			{
				if (!showAll.remove(tag))
				{
					showAll.add(tag);
				}
				renderList();
			}
		});
		return link;
	}

	private JPanel buildSectionHeader(Tag tag, int count)
	{
		JPanel h = new JPanel(new BorderLayout());
		h.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		h.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 4, 0, 0, tag.getColor()),
			new EmptyBorder(3, 6, 3, 6)));
		JLabel name = new JLabel(tag.getFullName());
		name.setFont(name.getFont().deriveFont(java.awt.Font.BOLD, textSize()));
		name.setForeground(Color.WHITE);
		name.setIconTextGap(7);
		icons.load(tag, name::setIcon);
		h.add(name, BorderLayout.CENTER);
		JLabel n = new JLabel(String.valueOf(count));
		n.setFont(n.getFont().deriveFont(java.awt.Font.BOLD, textSize()));
		n.setForeground(tag.getColor());
		h.add(n, BorderLayout.EAST);
		h.setAlignmentX(LEFT_ALIGNMENT);
		h.setMaximumSize(new Dimension(Integer.MAX_VALUE, h.getPreferredSize().height));
		return h;
	}

	private JPanel buildRow(Member m, long serverNow, boolean alt)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBackground(alt ? new Color(0x25, 0x25, 0x25) : ColorScheme.DARK_GRAY_COLOR);
		row.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.BORDER_COLOR),
			new EmptyBorder(4, 6, 4, 2)));
		row.setAlignmentX(LEFT_ALIGNMENT);

		String where = m.getWorld() > 0 ? "W" + m.getWorld() : "In game";
		String left = m.getExpiresAt() == 0 ? "" : " · " + timeLeft(m.getExpiresAt() - serverNow);
		String message = m.getStatus().isEmpty() ? ""
			: "<br><i><font color='" + hex(ColorScheme.TEXT_COLOR) + "'>" + esc(m.getStatus()) + "</font></i>";
		JLabel text = new JLabel();
		text.setFont(text.getFont().deriveFont(textSize()));
		text.setForeground(ColorScheme.TEXT_COLOR);
		setWrappedText(text, "<b>" + esc(m.getRsn()) + "</b><br>"
			+ "<font color='" + hex(MUTED) + "'>" + where + left + "</font>" + message, ROW_TEXT_WIDTH);
		row.add(text, BorderLayout.CENTER);

		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		return row;
	}

	// ------------------------------------------------------------------ small helpers

	private static String timeLeft(long ms)
	{
		long minutes = Math.max(1, (ms + 59_999) / 60_000);
		if (minutes >= 60)
		{
			long h = minutes / 60;
			long m = minutes % 60;
			return m == 0 ? h + "h left" : h + "h " + m + "m left";
		}
		return minutes + "m left";
	}

	/** Anything that came from the server is escaped before it goes into an HTML label. */
	static String esc(String s)
	{
		StringBuilder b = new StringBuilder(s.length());
		for (char c : s.toCharArray())
		{
			switch (c)
			{
				case '&':
					b.append("&amp;");
					break;
				case '<':
					b.append("&lt;");
					break;
				case '>':
					b.append("&gt;");
					break;
				case '"':
					b.append("&quot;");
					break;
				case '\'':
					b.append("&#39;");
					break;
				default:
					b.append(c);
			}
		}
		return b.toString();
	}

	private static String hex(Color c)
	{
		return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
	}

	private static JPanel column()
	{
		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setOpaque(false);
		return p;
	}

	private static JLabel heading(String text)
	{
		JLabel l = new JLabel(text);
		l.setForeground(Color.WHITE);
		l.setFont(l.getFont().deriveFont(java.awt.Font.BOLD, textSize() + 3f));
		l.setBorder(new EmptyBorder(0, 0, 0, 8));
		l.setAlignmentX(LEFT_ALIGNMENT);
		return l;
	}

	private static JLabel small(String text)
	{
		JLabel l = new JLabel(text);
		l.setForeground(MUTED);
		l.setFont(l.getFont().deriveFont(smallSize()));
		l.setBorder(new EmptyBorder(0, 0, 0, 8)); // a little slack so text is never clipped at the edge
		l.setAlignmentX(LEFT_ALIGNMENT);
		return l;
	}

	private static JLabel note(String text)
	{
		JLabel l = new JLabel();
		l.setForeground(MUTED);
		l.setFont(l.getFont().deriveFont(smallSize()));
		l.setAlignmentX(LEFT_ALIGNMENT);
		setWrappedText(l, esc(text), CONTENT_WIDTH);
		return l;
	}

	/**
	 * Sets HTML text that wraps at exactly widthPx REAL pixels. We do not use CSS "width:...px"
	 * for this, because Swing scales CSS pixels by the screen's DPI, so the text would overflow
	 * on some computers. Instead we ask Swing's HTML renderer how tall the text is at that width.
	 * The font must be set BEFORE calling this. htmlBody must already be escaped.
	 */
	static void setWrappedText(JLabel l, String htmlBody, int widthPx)
	{
		if (htmlBody == null || htmlBody.trim().isEmpty())
		{
			l.setText(" ");
			l.setPreferredSize(null);
			l.setMaximumSize(null);
			l.setMinimumSize(null);
			return;
		}
		l.setText("<html>" + htmlBody + "</html>");
		Object view = l.getClientProperty(BasicHTML.propertyKey);
		if (view instanceof View)
		{
			View v = (View) view;
			v.setSize(widthPx, 0);
			Dimension d = new Dimension(widthPx, (int) Math.ceil(v.getPreferredSpan(View.Y_AXIS)));
			l.setPreferredSize(d);
			l.setMaximumSize(d);
			l.setMinimumSize(d);
		}
	}

	private static java.awt.Component gap(int px)
	{
		return javax.swing.Box.createVerticalStrut(px);
	}

	private static JPanel wrapLeft(JPanel inner)
	{
		JPanel w = new JPanel(new BorderLayout());
		w.setOpaque(false);
		w.add(inner, BorderLayout.CENTER);
		w.setAlignmentX(LEFT_ALIGNMENT);
		return w;
	}

	private static void onEdt(Runnable r)
	{
		if (SwingUtilities.isEventDispatchThread())
		{
			r.run();
		}
		else
		{
			SwingUtilities.invokeLater(r);
		}
	}
}
