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

    public MetadataTablePanel() {
        model = new Model();
        table = new JTable(model);
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        table.setFillsViewportHeight(true);
        table.setRowHeight(24);
        table.getColumnModel().getColumn(0).setPreferredWidth(280);
        table.getColumnModel().getColumn(1).setPreferredWidth(110);
        table.getColumnModel().getColumn(2).setPreferredWidth(110);
        table.getColumnModel().getColumn(3).setPreferredWidth(110);
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
        int[] modelRows;
        if (allRows) {
            modelRows = new int[model.getRowCount()];
            for (int i = 0; i < modelRows.length; i++) modelRows[i] = i;
        } else {
            int[] selected = table.getSelectedRows();
            modelRows = new int[selected.length];
            for (int i = 0; i < selected.length; i++) {
                modelRows[i] = table.convertRowIndexToModel(selected[i]);
            }
        }
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
                "File", "Group", "Subject", "Section"
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
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return columnIndex > 0;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            MetadataRow row = metadataTable.rows().get(rowIndex);
            if (columnIndex == 0) return MetadataTable.displayName(row);
            if (columnIndex == 1) return row.group;
            if (columnIndex == 2) return row.subject;
            return row.section;
        }

        @Override
        public void setValueAt(Object value, int rowIndex, int columnIndex) {
            MetadataRow row = metadataTable.rows().get(rowIndex);
            String text = value == null ? "" : value.toString();
            if (columnIndex == 1) row.setLabels(text, row.subject, row.section);
            else if (columnIndex == 2) row.setLabels(row.group, text, row.section);
            else if (columnIndex == 3) row.setLabels(row.group, row.subject, text);
            fireTableRowsUpdated(rowIndex, rowIndex);
            if (editListener != null) editListener.run();
        }
    }

    private final class RowRenderer extends DefaultTableCellRenderer {
        private final Color unassigned = new Color(250, 248, 232);

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                boolean selected, boolean focus, int row, int column) {
            Component component = super.getTableCellRendererComponent(table, value,
                    selected, focus, row, column);
            if (!selected && model.metadataTable != null) {
                int modelRow = table.convertRowIndexToModel(row);
                MetadataRow metadataRow = model.metadataTable.rows().get(modelRow);
                component.setBackground(metadataRow.isAssigned()
                        ? Color.WHITE
                        : unassigned);
            }
            return component;
        }
    }
}
