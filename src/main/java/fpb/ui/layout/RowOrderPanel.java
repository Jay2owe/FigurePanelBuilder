/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.ui.layout;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeMap;

/** Row-ordering hub for arranging groups in the figure preview. */
public final class RowOrderPanel extends JPanel {

    private final DefaultListModel<GroupRow> model =
            new DefaultListModel<GroupRow>();
    private final JList<GroupRow> list = new JList<GroupRow>(model);
    private Runnable onChange;

    public RowOrderPanel(List<String> groups, List<List<String>> rows) {
        super(new BorderLayout(8, 4));
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        applyRows(groups, rows);
        list.setVisibleRowCount(Math.min(8, Math.max(3, model.getSize())));
        list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        list.setCellRenderer(new Renderer());
        if (model.getSize() > 0) list.setSelectedIndex(0);
        JScrollPane scroll = new JScrollPane(list);
        scroll.setPreferredSize(new Dimension(260, 132));
        add(scroll, BorderLayout.CENTER);
        add(buttons(), BorderLayout.EAST);
    }

    public void setOnChange(Runnable onChange) {
        this.onChange = onChange;
    }

    public void setRows(List<String> groups, List<List<String>> rows) {
        applyRows(groups, rows);
        if (model.getSize() > 0) list.setSelectedIndex(0);
        fireChange();
    }

    public List<List<String>> rows() {
        List<String> ordered = new ArrayList<String>();
        List<Integer> rowNumbers = new ArrayList<Integer>();
        for (int i = 0; i < model.getSize(); i++) {
            GroupRow row = model.getElementAt(i);
            ordered.add(row.group);
            rowNumbers.add(Integer.valueOf(row.rowNumber));
        }
        return rowsFromAssignments(ordered, rowNumbers);
    }

    public void moveSelectedForTest(int delta) {
        moveSelected(delta);
    }

    public void adjustSelectedRowForTest(int delta) {
        adjustSelectedRow(delta);
    }

    public void allInOneRowForTest() {
        setAllInOneRow();
    }

    public void oneGroupPerRowForTest() {
        setOneGroupPerRow();
    }

    static List<List<String>> rowsFromAssignments(List<String> orderedGroups,
            List<Integer> rowNumbers) {
        TreeMap<Integer, List<String>> byRow =
                new TreeMap<Integer, List<String>>();
        if (orderedGroups != null && rowNumbers != null) {
            for (int i = 0; i < orderedGroups.size()
                    && i < rowNumbers.size(); i++) {
                String group = clean(orderedGroups.get(i));
                if (group.isEmpty()) continue;
                Integer assigned = rowNumbers.get(i);
                int rowNumber = assigned == null ? 1
                        : Math.max(1, assigned.intValue());
                List<String> row = byRow.get(Integer.valueOf(rowNumber));
                if (row == null) {
                    row = new ArrayList<String>();
                    byRow.put(Integer.valueOf(rowNumber), row);
                }
                row.add(group);
            }
        }
        return new ArrayList<List<String>>(byRow.values());
    }

    static List<List<String>> allInOneRow(List<String> groups) {
        List<List<String>> rows = new ArrayList<List<String>>();
        rows.add(cleanGroups(groups));
        return rows;
    }

    static List<List<String>> oneGroupPerRow(List<String> groups) {
        List<List<String>> rows = new ArrayList<List<String>>();
        for (String group : cleanGroups(groups)) {
            rows.add(new ArrayList<String>(Collections.singletonList(group)));
        }
        return rows;
    }

    private JPanel buttons() {
        JPanel buttons = new JPanel();
        buttons.setOpaque(false);
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.Y_AXIS));
        JButton up = button("Move up");
        JButton down = button("Move down");
        JButton rowUp = button("Row +");
        JButton rowDown = button("Row -");
        JButton oneRow = button("All in one row");
        JButton eachRow = button("One group per row");
        buttons.add(up);
        buttons.add(Box.createVerticalStrut(4));
        buttons.add(down);
        buttons.add(Box.createVerticalStrut(10));
        buttons.add(rowUp);
        buttons.add(Box.createVerticalStrut(4));
        buttons.add(rowDown);
        buttons.add(Box.createVerticalStrut(10));
        buttons.add(oneRow);
        buttons.add(Box.createVerticalStrut(4));
        buttons.add(eachRow);

        up.addActionListener(e -> moveSelected(-1));
        down.addActionListener(e -> moveSelected(1));
        rowUp.addActionListener(e -> adjustSelectedRow(1));
        rowDown.addActionListener(e -> adjustSelectedRow(-1));
        oneRow.addActionListener(e -> setAllInOneRow());
        eachRow.addActionListener(e -> setOneGroupPerRow());
        return buttons;
    }

    private void applyRows(List<String> groups, List<List<String>> rows) {
        List<String> names = cleanGroups(groups);
        List<List<String>> safeRows = normalizeRows(names, rows);
        model.clear();
        for (int r = 0; r < safeRows.size(); r++) {
            for (String group : safeRows.get(r)) {
                model.addElement(new GroupRow(group, r + 1));
            }
        }
    }

    private static List<List<String>> normalizeRows(List<String> groups,
            List<List<String>> rows) {
        List<List<String>> out = new ArrayList<List<String>>();
        List<String> remaining = new ArrayList<String>(groups);
        if (rows != null) {
            for (List<String> input : rows) {
                List<String> row = new ArrayList<String>();
                if (input != null) {
                    for (String value : input) {
                        String group = clean(value);
                        if (remaining.remove(group)) row.add(group);
                    }
                }
                if (!row.isEmpty()) out.add(row);
            }
        }
        for (String group : remaining) {
            out.add(new ArrayList<String>(Collections.singletonList(group)));
        }
        if (out.isEmpty() && !groups.isEmpty()) out.add(new ArrayList<String>(groups));
        return out;
    }

    private void moveSelected(int delta) {
        int index = list.getSelectedIndex();
        int next = index + delta;
        if (index < 0 || next < 0 || next >= model.getSize()) return;
        GroupRow row = model.getElementAt(index);
        model.removeElementAt(index);
        model.add(next, row);
        list.setSelectedIndex(next);
        fireChange();
    }

    private void adjustSelectedRow(int delta) {
        int[] selected = list.getSelectedIndices();
        if (selected.length == 0) return;
        for (int index : selected) {
            GroupRow row = model.getElementAt(index);
            model.setElementAt(new GroupRow(row.group,
                    Math.max(1, row.rowNumber + delta)), index);
        }
        list.setSelectedIndices(selected);
        fireChange();
    }

    private void setAllInOneRow() {
        List<String> groups = currentGroups();
        applyRows(groups, allInOneRow(groups));
        fireChange();
    }

    private void setOneGroupPerRow() {
        List<String> groups = currentGroups();
        applyRows(groups, oneGroupPerRow(groups));
        fireChange();
    }

    private List<String> currentGroups() {
        List<String> groups = new ArrayList<String>();
        for (int i = 0; i < model.getSize(); i++) {
            groups.add(model.getElementAt(i).group);
        }
        return groups;
    }

    private void fireChange() {
        if (onChange != null) onChange.run();
    }

    private static List<String> cleanGroups(List<String> groups) {
        List<String> out = new ArrayList<String>();
        if (groups != null) {
            for (String group : groups) {
                String clean = clean(group);
                if (!clean.isEmpty() && !out.contains(clean)) out.add(clean);
            }
        }
        return out;
    }

    private static JButton button(String label) {
        JButton button = new JButton(label);
        Dimension preferred = button.getPreferredSize();
        button.setMaximumSize(new Dimension(Math.max(132, preferred.width), 26));
        button.setAlignmentX(Component.LEFT_ALIGNMENT);
        return button;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static final class GroupRow {
        final String group;
        final int rowNumber;

        GroupRow(String group, int rowNumber) {
            this.group = clean(group);
            this.rowNumber = Math.max(1, rowNumber);
        }
    }

    private static final class Renderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value,
                int index, boolean selected, boolean focus) {
            Component component = super.getListCellRendererComponent(list, value,
                    index, selected, focus);
            if (component instanceof javax.swing.JLabel && value instanceof GroupRow) {
                GroupRow row = (GroupRow) value;
                ((javax.swing.JLabel) component).setText(
                        "Row " + row.rowNumber + "    " + row.group);
            }
            return component;
        }
    }
}
