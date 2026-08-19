/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package fpb.ui;

import fpb.meta.LabelStrategy;
import fpb.meta.MetadataTable;
import fpb.meta.MetadataTableIO;
import fpb.meta.RegexStrategy;
import fpb.meta.SubfolderStrategy;
import fpb.meta.TokenStrategy;
import fpb.io.ImageLoader;
import fpb.io.ImageSource;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.SwingWorker;
import javax.swing.JTextField;
import javax.swing.filechooser.FileNameExtensionFilter;

/** First wizard step: choose images and label group/subject/section metadata. */
public final class Step1Images implements WizardStep {

    private final FPBWizard.Context context;
    private final Runnable quickGridAction;
    private final JPanel panel;
    private final JTextField folderField;
    private final ToggleSwitch recursiveToggle;
    private final JRadioButton filenameStrategy;
    private final JRadioButton subfolderStrategy;
    private final JRadioButton csvStrategy;
    private final TokenPicker tokenPicker;
    private final MetadataTablePanel tablePanel;
    private final JLabel summaryLabel;
    private final JButton retryFolderButton;
    private boolean loading;
    private File retryFolder;
    private boolean retryRecursiveFallback;
    private SwingWorker<FolderScan, Void> folderWorker;

    public Step1Images(FPBWizard.Context context, Runnable quickGridAction) {
        this.context = context;
        this.quickGridAction = quickGridAction;
        panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(14, 16, 12, 16));

        JPanel top = new JPanel();
        top.setLayout(new javax.swing.BoxLayout(top, javax.swing.BoxLayout.Y_AXIS));

        JPanel folderRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        folderRow.add(new JLabel("Folder"));
        folderField = new JTextField(34);
        folderRow.add(folderField);
        JButton browse = new JButton("Browse");
        browse.addActionListener(new java.awt.event.ActionListener() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                browseForFolder();
            }
        });
        folderRow.add(browse);
        folderRow.add(new JLabel("Include subfolders"));
        recursiveToggle = new ToggleSwitch(false);
        recursiveToggle.addChangeListener(new Runnable() {
            @Override
            public void run() {
                if (!loading && context.folder != null) {
                    loadFolderAsync(context.folder, false);
                }
            }
        });
        folderRow.add(recursiveToggle);
        top.add(folderRow);

        JPanel strategyRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        strategyRow.add(new JLabel("Get labels from:"));
        filenameStrategy = new JRadioButton("Filename", true);
        subfolderStrategy = new JRadioButton("Subfolder");
        csvStrategy = new JRadioButton("CSV");
        ButtonGroup group = new ButtonGroup();
        group.add(filenameStrategy);
        group.add(subfolderStrategy);
        group.add(csvStrategy);
        strategyRow.add(filenameStrategy);
        strategyRow.add(subfolderStrategy);
        strategyRow.add(csvStrategy);
        JButton advanced = new JButton("Advanced...");
        advanced.addActionListener(new java.awt.event.ActionListener() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                showAdvancedRegex();
            }
        });
        strategyRow.add(advanced);
        top.add(strategyRow);

        tokenPicker = new TokenPicker();
        tokenPicker.setChangeListener(new Runnable() {
            @Override
            public void run() {
                if (!loading && filenameStrategy.isSelected()) applyFilenameStrategy();
            }
        });
        top.add(tokenPicker);

        filenameStrategy.addActionListener(new java.awt.event.ActionListener() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                applyFilenameStrategy();
            }
        });
        subfolderStrategy.addActionListener(new java.awt.event.ActionListener() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                applySubfolderStrategy();
            }
        });
        csvStrategy.addActionListener(new java.awt.event.ActionListener() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                tokenPicker.setEnabled(false);
            }
        });

        panel.add(top, BorderLayout.NORTH);

        JPanel centre = new JPanel(new BorderLayout(6, 6));
        JPanel summaryRow = new JPanel(new BorderLayout(8, 0));
        summaryLabel = new JLabel("Choose a folder to populate the table.");
        summaryRow.add(summaryLabel, BorderLayout.CENTER);
        retryFolderButton = new JButton("Retry");
        retryFolderButton.setVisible(false);
        retryFolderButton.addActionListener(new java.awt.event.ActionListener() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                if (retryFolder != null) {
                    loadFolderAsync(retryFolder, retryRecursiveFallback);
                }
            }
        });
        summaryRow.add(retryFolderButton, BorderLayout.EAST);
        centre.add(summaryRow, BorderLayout.NORTH);
        tablePanel = new MetadataTablePanel();
        tablePanel.setEditListener(new Runnable() {
            @Override
            public void run() {
                context.tableHandEdited = true;
                context.quickGridRequested = false;
                context.invalidateGuidedDownstream(0);
                updateSummary();
            }
        });
        centre.add(tablePanel, BorderLayout.CENTER);

        JPanel bulkRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        bulkRow.add(new JLabel("Double-click a cell to edit, or bulk edit"));
        final JComboBox<String> bulkField = new FitComboBox<String>(new String[] {
                "Group", "Subject", "Section"
        });
        bulkRow.add(bulkField);
        final JTextField bulkValue = new JTextField(16);
        bulkRow.add(bulkValue);
        final JComboBox<String> bulkScope = new FitComboBox<String>(new String[] {
                "Selected rows", "All rows"
        });
        bulkRow.add(bulkScope);
        JButton applyBulk = new JButton("Apply");
        applyBulk.addActionListener(new java.awt.event.ActionListener() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                MetadataTablePanel.MetadataField field =
                        MetadataTablePanel.MetadataField.values()[
                                bulkField.getSelectedIndex()];
                int changed = applyBulkMetadata(field, bulkValue.getText(),
                        bulkScope.getSelectedIndex() == 1);
                if (changed == 0) {
                    showMessage("Select one or more table rows, or choose All rows.");
                }
            }
        });
        bulkRow.add(applyBulk);
        centre.add(bulkRow, BorderLayout.SOUTH);
        panel.add(centre, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout());
        JPanel csvButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        JButton importCsv = new JButton("Import CSV");
        importCsv.addActionListener(new java.awt.event.ActionListener() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                browseForCsvImport();
            }
        });
        JButton exportCsv = new JButton("Export CSV");
        exportCsv.addActionListener(new java.awt.event.ActionListener() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                browseForCsvExport();
            }
        });
        csvButtons.add(importCsv);
        csvButtons.add(exportCsv);
        bottom.add(csvButtons, BorderLayout.CENTER);

        JButton quickGrid = new JButton("Quick grid");
        quickGrid.addActionListener(new java.awt.event.ActionListener() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                if (quickGridAction != null) quickGridAction.run();
            }
        });
        bottom.add(quickGrid, BorderLayout.WEST);
        panel.add(bottom, BorderLayout.SOUTH);
    }

    @Override
    public String title() {
        return "Images";
    }

    @Override
    public String nextTitle() {
        return "Channels";
    }

    @Override
    public JPanel component() {
        return panel;
    }

    @Override
    public void onShow() {
        if (context.quickGridRequested && context.folder != null) {
            context.quickGridRequested = false;
            context.invalidateGuidedDownstream(0);
            loadFolder(context.folder, true);
        }
        if (context.metadataTable != null) {
            tablePanel.setMetadataTable(context.metadataTable);
            updateSummary();
        }
    }

    @Override
    public boolean canAdvance() {
        return !loading && tablePanel.commitActiveEdit()
                && context.metadataTable != null && context.metadataTable.fileCount() > 0
                && context.metadataTable.unassignedCount() == 0;
    }

    public void chooseFolder(File folder) {
        loadFolder(folder, true);
    }

    public MetadataTable metadataTable() {
        return context.metadataTable;
    }

    public String summaryText() {
        return summaryLabel.getText();
    }

    public String selectedStrategyName() {
        if (subfolderStrategy.isSelected()) return "Subfolder";
        if (csvStrategy.isSelected()) return "CSV";
        return "Filename";
    }

    public TokenPicker tokenPicker() {
        return tokenPicker;
    }

    public MetadataTablePanel tablePanel() {
        return tablePanel;
    }

    public int applyBulkMetadata(MetadataTablePanel.MetadataField field,
            String value, boolean allRows) {
        return tablePanel.applyBulkValue(field, value, allRows);
    }

    public void importCsv(File csvFile) throws IOException {
        if (!tablePanel.commitActiveEdit()) {
            throw new IOException("Finish the active metadata edit before importing CSV.");
        }
        if (context.metadataTable == null) {
            throw new IOException("Choose an image folder before importing CSV metadata.");
        }
        MetadataTableIO.ImportResult imported =
                MetadataTableIO.importCsv(context.metadataTable, csvFile);
        if (!imported.isComplete()) {
            throw new IOException(imported.problemSummary());
        }
        csvStrategy.setSelected(true);
        tokenPicker.setEnabled(false);
        context.tableHandEdited = false;
        context.quickGridRequested = false;
        context.invalidateGuidedDownstream(0);
        tablePanel.setMetadataTable(context.metadataTable);
        updateSummary();
    }

    public void exportCsv(File csvFile) throws IOException {
        if (!tablePanel.commitActiveEdit()) {
            throw new IOException("Finish the active metadata edit before exporting CSV.");
        }
        if (context.metadataTable == null) {
            throw new IOException("Choose an image folder before exporting CSV metadata.");
        }
        MetadataTableIO.exportCsv(context.metadataTable, csvFile);
    }

    private void browseForFolder() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (context.folder != null) chooser.setCurrentDirectory(context.folder);
        if (chooser.showOpenDialog(panel) == JFileChooser.APPROVE_OPTION) {
            loadFolderAsync(chooser.getSelectedFile(), true);
        }
    }

    private void browseForCsvImport() {
        JFileChooser chooser = csvChooser();
        if (chooser.showOpenDialog(panel) == JFileChooser.APPROVE_OPTION) {
            try {
                importCsv(chooser.getSelectedFile());
            } catch (IOException failure) {
                showMessage(failure.getMessage());
            }
        }
    }

    private void browseForCsvExport() {
        JFileChooser chooser = csvChooser();
        if (chooser.showSaveDialog(panel) == JFileChooser.APPROVE_OPTION) {
            try {
                exportCsv(chooser.getSelectedFile());
            } catch (IOException failure) {
                showMessage(failure.getMessage());
            }
        }
    }

    private static JFileChooser csvChooser() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("CSV files", "csv"));
        return chooser;
    }

    private void loadFolder(File folder, boolean allowRecursiveFallback) {
        if (folder == null) return;
        context.quickGridRequested = false;
        context.invalidateGuidedDownstream(0);
        loading = true;
        try {
            File root = folder.getAbsoluteFile();
            retryFolder = root;
            retryRecursiveFallback = allowRecursiveFallback;
            context.folder = root;
            folderField.setText(root.getAbsolutePath());
            applyFolderScan(scanFolder(root, recursiveToggle.isSelected(),
                    allowRecursiveFallback));
        } catch (IOException failure) {
            showFolderFailure(failure);
        } finally {
            loading = false;
        }
    }

    private void loadFolderAsync(File folder, boolean allowRecursiveFallback) {
        if (folder == null) return;
        if (folderWorker != null && !folderWorker.isDone()) {
            folderWorker.cancel(true);
        }
        context.imagePreloader.cancel();
        final File root = folder.getAbsoluteFile();
        final boolean recursive = recursiveToggle.isSelected();
        final boolean fallback = allowRecursiveFallback;
        retryFolder = root;
        retryRecursiveFallback = fallback;
        context.quickGridRequested = false;
        context.invalidateGuidedDownstream(0);
        context.folder = root;
        context.recursive = recursive;
        context.metadataTable = null;
        folderField.setText(root.getAbsolutePath());
        loading = true;
        retryFolderButton.setVisible(false);
        summaryLabel.setText("<html><b>Checking images...</b> Online-only "
                + "files will be requested from the cloud provider before they "
                + "are opened.</html>");

        final SwingWorker<FolderScan, Void> worker =
                new SwingWorker<FolderScan, Void>() {
                    @Override
                    protected FolderScan doInBackground() throws Exception {
                        return scanFolder(root, recursive, fallback);
                    }

                    @Override
                    protected void done() {
                        if (folderWorker != this) return;
                        try {
                            if (!isCancelled()) {
                                applyFolderScan(get());
                                startPreviewPreload();
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            showFolderFailure(new IOException(
                                    "Folder scan was interrupted.", interrupted));
                        } catch (IOException failure) {
                            showFolderFailure(failure);
                        } catch (java.util.concurrent.ExecutionException failure) {
                            Throwable cause = failure.getCause();
                            showFolderFailure(cause instanceof IOException
                                    ? (IOException) cause
                                    : new IOException("Could not scan the image folder.",
                                            cause));
                        } finally {
                            loading = false;
                        }
                    }
                };
        folderWorker = worker;
        worker.execute();
    }

    private static FolderScan scanFolder(File root, boolean recursive,
            boolean allowRecursiveFallback) throws IOException {
        List<ImageSource> sources = ImageLoader.discoverImageSources(root, recursive);
        boolean effectiveRecursive = recursive;
        if (sources.isEmpty() && allowRecursiveFallback && !recursive) {
            List<ImageSource> recursiveSources = ImageLoader.discoverImageSources(
                    root, true);
            if (!recursiveSources.isEmpty()) {
                sources = recursiveSources;
                effectiveRecursive = true;
            }
        }
        return new FolderScan(root, effectiveRecursive, sources);
    }

    private void applyFolderScan(FolderScan scan) throws IOException {
        context.folder = scan.root;
        context.recursive = scan.recursive;
        recursiveToggle.setSelected(scan.recursive);
        retryFolderButton.setVisible(false);
        if (scan.sources.isEmpty()) {
            context.metadataTable = MetadataTable.emptySources(scan.root,
                    scan.sources);
            tablePanel.setMetadataTable(context.metadataTable);
            updateSummary();
            return;
        }
        LabelStrategy strategy = MetadataTable.suggestSources(scan.root,
                scan.sources);
        context.metadataTable = MetadataTable.fromSources(scan.root, scan.sources,
                strategy);
        selectStrategy(strategy);
        tablePanel.setMetadataTable(context.metadataTable);
        updateSummary();
    }

    private void showFolderFailure(IOException failure) {
        context.imagePreloader.cancel();
        File root = context.folder == null ? retryFolder : context.folder;
        if (root != null) {
            context.metadataTable = new MetadataTable(root,
                    Collections.<fpb.meta.MetadataRow>emptyList());
            tablePanel.setMetadataTable(context.metadataTable);
        } else {
            context.metadataTable = null;
        }
        String explanation = failure.getMessage() == null
                ? "The image folder could not be read."
                : failure.getMessage();
        summaryLabel.setText("<html><body style='width: 690px'><b>Could not "
                + "open the images.</b> " + escapeHtml(explanation)
                + "<br>After the download completes, click Retry. You can also "
                + "use Browse to choose another folder.</body></html>");
        retryFolderButton.setVisible(retryFolder != null);
    }

    private void startPreviewPreload() {
        if (context.metadataTable == null || context.metadataTable.rows().isEmpty()) {
            return;
        }
        final List<ImageSource> sources = new java.util.ArrayList<ImageSource>();
        for (fpb.meta.MetadataRow row : context.metadataTable.rows()) {
            sources.add(row.source);
        }
        final ImageLoader.ZMode zMode = ImageLoader.ZMode.fromString(
                context.zHandling);
        context.imagePreloader.preload(sources, zMode, null);
    }

    private static String escapeHtml(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static final class FolderScan {
        final File root;
        final boolean recursive;
        final List<ImageSource> sources;

        FolderScan(File root, boolean recursive, List<ImageSource> sources) {
            this.root = root;
            this.recursive = recursive;
            this.sources = sources;
        }
    }

    private void selectStrategy(LabelStrategy strategy) {
        loading = true;
        try {
            if (strategy instanceof SubfolderStrategy) {
                subfolderStrategy.setSelected(true);
                tokenPicker.setEnabled(false);
            } else {
                filenameStrategy.setSelected(true);
                tokenPicker.setEnabled(true);
                ImageSource sample = context.metadataTable == null
                        || context.metadataTable.rows().isEmpty()
                        ? null
                        : context.metadataTable.rows().get(0).source;
                tokenPicker.setSampleSource(sample, strategy instanceof TokenStrategy
                        ? (TokenStrategy) strategy
                        : null);
            }
        } finally {
            loading = false;
        }
    }

    private void applyFilenameStrategy() {
        tokenPicker.setEnabled(true);
        applyStrategy(tokenPicker.strategy());
    }

    private void applySubfolderStrategy() {
        tokenPicker.setEnabled(false);
        applyStrategy(new SubfolderStrategy());
    }

    private void applyStrategy(LabelStrategy strategy) {
        if (context.metadataTable == null) return;
        if (!tablePanel.commitActiveEdit()) {
            showMessage("Finish the active metadata edit before changing label strategy.");
            return;
        }
        strategy.apply(context.metadataTable);
        context.quickGridRequested = false;
        context.invalidateGuidedDownstream(0);
        tablePanel.setMetadataTable(context.metadataTable);
        updateSummary();
    }

    private void showAdvancedRegex() {
        if (context.metadataTable == null) {
            showMessage("Choose an image folder before applying a regular expression.");
            return;
        }
        JPanel form = new JPanel(new GridLayout(0, 2, 6, 6));
        JTextField regex = new JTextField("(.+?)_(.+?)(?:_(.+?))?\\..+");
        JTextField groupCapture = new JTextField("1");
        JTextField subjectCapture = new JTextField("2");
        JTextField sectionCapture = new JTextField("3");
        form.add(new JLabel("Regular expression"));
        form.add(regex);
        form.add(new JLabel("Group capture"));
        form.add(groupCapture);
        form.add(new JLabel("Subject capture"));
        form.add(subjectCapture);
        form.add(new JLabel("Section capture"));
        form.add(sectionCapture);
        int result = JOptionPane.showConfirmDialog(panel, form, "Advanced metadata",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result == JOptionPane.OK_OPTION) {
            try {
                RegexStrategy strategy = new RegexStrategy(regex.getText(),
                        Integer.parseInt(groupCapture.getText().trim()),
                        Integer.parseInt(subjectCapture.getText().trim()),
                        Integer.parseInt(sectionCapture.getText().trim()));
                applyStrategy(strategy);
                tokenPicker.setEnabled(false);
            } catch (RuntimeException failure) {
                showMessage(failure.getMessage());
            }
        }
    }

    private void updateSummary() {
        if (context.metadataTable == null) {
            summaryLabel.setText("Choose a folder to populate the table.");
        } else {
            summaryLabel.setText(context.metadataTable.summary());
        }
    }

    private void showMessage(String message) {
        JOptionPane.showMessageDialog(panel, message, "Figure Panel Builder",
                JOptionPane.INFORMATION_MESSAGE);
    }

}
