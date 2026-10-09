/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.ui;

import fpb.meta.MetadataRow;
import fpb.meta.MetadataTable;

import java.awt.Color;
import java.awt.Component;

import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

/** Editable Swing view of the metadata table. */
public final class MetadataTablePanel extends JScrollPane {

    public enum MetadataField {
        GROUP,
        SUBJECT,
        SECTION
    }

    private final Model model;
    private final JTable table;
    private Runnable editListener;
    private Runnable inclusionListener;

    public MetadataTablePanel() {
        model = new Model();
        table = new JTable(model);
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        table.setFillsViewportHeight(true);
        table.setRowHeight(24);
        table.getColumnModel().getColumn(0).setPreferredWidth(40);
        table.getColumnModel().getColumn(0).setMaxWidth(60);
        table.getColumnModel().getColumn(1).setPreferredWidth(280);
        table.getColumnModel().getColumn(2).setPreferredWidth(110);
        table.getColumnModel().getColumn(3).setPreferredWidth(110);
        table.getColumnModel().getColumn(4).setPreferredWidth(110);
        table.setDefaultRenderer(Object.class, new RowRenderer());
        setViewportView(table);
    }

    public void setMetadataTable(MetadataTable metadataTable) {
        if (table.isEditing() && table.getCellEditor() != null) {
            table.getCellEditor().cancelCellEditing();
        }
        model.setMetadataTable(metadataTable);
    }

    public MetadataTable metadataTable() {
        return model.metadataTable;
    }

    public JTable table() {
        return table;
    }

    public void setEditListener(Runnable listener) {
        editListener = listener;
    }

    /** Called whenever an image is ticked or unticked. */
    public void setInclusionListener(Runnable listener) {
        inclusionListener = listener;
    }

    /** Ticks or unticks the selected rows, or every row when requested. */
    public int setIncluded(boolean included, boolean allRows) {
        if (!commitActiveEdit() || model.metadataTable == null) return 0;
        int[] modelRows = targetRows(allRows);
        if (modelRows.length == 0) return 0;
        for (int modelRow : modelRows) {
            model.metadataTable.rows().get(modelRow).included = included;
        }
        model.fireTableDataChanged();
        if (inclusionListener != null) inclusionListener.run();
        return modelRows.length;
    }

    private int[] targetRows(boolean allRows) {
        if (allRows) {
            int[] rows = new int[model.getRowCount()];
            for (int i = 0; i < rows.length; i++) rows[i] = i;
            return rows;
        }
        int[] selected = table.getSelectedRows();
        int[] rows = new int[selected.length];
        for (int i = 0; i < selected.length; i++) {
            rows[i] = table.convertRowIndexToModel(selected[i]);
        }
        return rows;
    }

    /** Commits the live editor into the metadata model before validation or I/O. */
    public boolean commitActiveEdit() {
        if (!table.isEditing()) return true;
        javax.swing.table.TableCellEditor editor = table.getCellEditor();
        return editor == null || editor.stopCellEditing();
    }

    /** Applies one metadata value to selected rows, or every row when requested. */
    public int applyBulkValue(MetadataField field, String value, boolean allRows) {
        if (field == null) throw new IllegalArgumentException("field must not be null");
        if (!commitActiveEdit() || model.metadataTable == null) return 0;
        int[] modelRows = targetRows(allRows);
        if (modelRows.length == 0) return 0;
        String text = value == null ? "" : value;
        int minimum = Integer.MAX_VALUE;
        int maximum = -1;
        for (int modelRow : modelRows) {
            MetadataRow row = model.metadataTable.rows().get(modelRow);
            if (field == MetadataField.GROUP) {
                row.setLabels(text, row.subject, row.section);
            } else if (field == MetadataField.SUBJECT) {
                row.setLabels(row.group, text, row.section);
            } else {
                row.setLabels(row.group, row.subject, text);
            }
            minimum = Math.min(minimum, modelRow);
            maximum = Math.max(maximum, modelRow);
        }
        model.fireTableRowsUpdated(minimum, maximum);
        if (editListener != null) editListener.run();
        return modelRows.length;
    }

    private final class Model extends AbstractTableModel {
        private final String[] columns = new String[] {
                "Use", "File", "Group", "Subject", "Section"
        };
        private MetadataTable metadataTable;

        void setMetadataTable(MetadataTable table) {
            metadataTable = table;
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return metadataTable == null ? 0 : metadataTable.rows().size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return columnIndex == 0 ? Boolean.class : Object.class;
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return columnIndex != 1;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            MetadataRow row = metadataTable.rows().get(rowIndex);
            if (columnIndex == 0) return Boolean.valueOf(row.included);
            if (columnIndex == 1) return MetadataTable.displayName(row);
            if (columnIndex == 2) return row.group;
            if (columnIndex == 3) return row.subject;
            return row.section;
        }

        @Override
        public void setValueAt(Object value, int rowIndex, int columnIndex) {
            MetadataRow row = metadataTable.rows().get(rowIndex);
            if (columnIndex == 0) {
                row.included = Boolean.TRUE.equals(value);
                fireTableRowsUpdated(rowIndex, rowIndex);
                if (inclusionListener != null) inclusionListener.run();
                return;
            }
            String text = value == null ? "" : value.toString();
            if (columnIndex == 2) row.setLabels(text, row.subject, row.section);
            else if (columnIndex == 3) row.setLabels(row.group, text, row.section);
            else if (columnIndex == 4) row.setLabels(row.group, row.subject, text);
            fireTableRowsUpdated(rowIndex, rowIndex);
            if (editListener != null) editListener.run();
        }
    }

    private final class RowRenderer extends DefaultTableCellRenderer {
        private final Color unassigned = new Color(250, 248, 232);
        private final Color leftOut = new Color(150, 150, 150);

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                boolean selected, boolean focus, int row, int column) {
            Component component = super.getTableCellRendererComponent(table, value,
                    selected, focus, row, column);
            if (!selected && model.metadataTable != null) {
                int modelRow = table.convertRowIndexToModel(row);
                MetadataRow metadataRow = model.metadataTable.rows().get(modelRow);
                component.setBackground(metadataRow.isAssigned()
                        || !metadataRow.included
                        ? Color.WHITE
                        : unassigned);
                component.setForeground(metadataRow.included
                        ? table.getForeground() : leftOut);
            }
            return component;
        }
    }
}
