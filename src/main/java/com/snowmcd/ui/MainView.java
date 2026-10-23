package com.snowmcd.ui;

import com.snowmcd.core.DownloadPlan;
import com.snowmcd.core.DownloadReport;
import com.snowmcd.core.DownloadTask;
import com.snowmcd.core.FailedDownload;
import com.snowmcd.core.MinecraftDownloader;
import com.snowmcd.core.ProgressListener;
import com.snowmcd.core.ProgressSnapshot;
import com.snowmcd.model.VersionInfo;
import com.snowmcd.model.VersionManifest;
import com.snowmcd.util.AppPaths;
import com.snowmcd.util.Log;
import com.snowmcd.util.MirrorInjector;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.prefs.Preferences;

/** Builds and drives the SnowMCD user interface. */
public final class MainView {

    private static final String APP_VERSION = "1.0.0";
    private static final String CSS = MainView.class.getResource("/com/snowmcd/ui/app.css").toExternalForm();

    private final Stage stage;
    private final Preferences prefs = Preferences.userRoot().node("com/snowmcd");
    private final MinecraftDownloader downloader = new MinecraftDownloader();

    private final BorderPane root = new BorderPane();
    private final StackPane contentStack = new StackPane();
    private final ObservableList<VersionInfo> versions = FXCollections.observableArrayList();
    private final FilteredList<VersionInfo> filteredVersions = new FilteredList<>(versions, version -> true);
    private final TableView<VersionInfo> versionTable = new TableView<>();
    private final TextField searchField = new TextField();
    private final ComboBox<String> filterBox = new ComboBox<>();
    private final TextField dirField = new TextField();
    private final Button browseButton = new Button("浏览…");
    private final Button refreshButton = new Button("刷新清单");
    private final Button startButton = new Button("开始下载");
    private final Button cancelButton = new Button("取消");
    private final Label mirrorStatusLabel = new Label("● 连接镜像…");
    private final Label versionCountLabel = new Label("—");
    private final Label phaseLabel = new Label("准备就绪");
    private final Label currentFileLabel = new Label("等待开始");
    private final Label percentLabel = new Label("0%");
    private final ProgressBar progressBar = new ProgressBar(0);
    private final Label speedLabel = new Label("—");
    private final Label sizeLabel = new Label("—");
    private final Label etaLabel = new Label("—");
    private final Label filesLabel = new Label("0 / 0");
    private final Label failedLabel = new Label("0");
    private final Label statusLabel = new Label("双击列表中的版本也可以直接开始");
    private final VBox progressCard = new VBox(10);
    private final VBox browseBlock = new VBox(12);
    private final Spinner<Integer> concurrencySpinner = new Spinner<>();
    private final Spinner<Integer> retriesSpinner = new Spinner<>();
    private final TextField settingsDirField = new TextField();
    private final ProgressIndicator loadingIndicator = new ProgressIndicator();
    private final Label loadingLabel = new Label("正在从 BMCLAPI 获取版本清单…");
    private final StackPane loadingPane = new StackPane();
    private final ToggleGroup navGroup = new ToggleGroup();
    private final List<ToggleButton> navButtons = new java.util.ArrayList<>();

    private VBox downloadPane;
    private VBox settingsPane;
    private VBox aboutPane;
    private VersionManifest manifest;
    private volatile boolean busy;
    private volatile boolean manifestLoading;
    private boolean autoStartConsumed;
    private DownloadReport lastReport;

    public MainView(Stage stage) {
        this.stage = stage;
        build();
        stage.setOnCloseRequest(this::onCloseRequest);
        Platform.runLater(() -> loadManifestAsync(false));
    }

    public Parent getRoot() {
        return root;
    }

    private void build() {
        root.getStyleClass().add("app-root");
        root.setTop(buildHeader());
        root.setLeft(buildSidebar());
        downloadPane = buildDownloadPane();
        settingsPane = buildSettingsPane();
        aboutPane = buildAboutPane();
        contentStack.getChildren().addAll(downloadPane, settingsPane, aboutPane);
        contentStack.getStyleClass().add("content-stack");
        root.setCenter(contentStack);
        showPane(0);
    }

    // ------------------------------------------------------------------ header / nav

    private Node buildHeader() {
        Label glyph = new Label("❄");
        glyph.getStyleClass().add("logo-glyph");
        StackPane badge = new StackPane(glyph);
        badge.getStyleClass().add("logo-badge");

        Label title = new Label("SnowMCD");
        title.getStyleClass().add("app-title");
        Label subtitle = new Label("Minecraft 全量下载器 · 标准 .minecraft 目录 · BMCLAPI 镜像");
        subtitle.getStyleClass().add("app-subtitle");
        VBox titles = new VBox(2, title, subtitle);
        HBox.setHgrow(titles, Priority.ALWAYS);

        mirrorStatusLabel.getStyleClass().add("pill");
        Label versionPill = new Label("v" + APP_VERSION);
        versionPill.getStyleClass().add("pill-muted");

        HBox header = new HBox(14, badge, titles, mirrorStatusLabel, versionPill);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("header");
        return header;
    }

    private Node buildSidebar() {
        VBox sidebar = new VBox(6);
        sidebar.getStyleClass().add("sidebar");

        Label section = new Label("导航");
        section.getStyleClass().add("sidebar-section");

        ToggleButton download = navButton("版本库", 0);
        ToggleButton settings = navButton("设置", 1);
        ToggleButton about = navButton("关于", 2);
        download.setSelected(true);

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        Label source = new Label("下载源\nBMCLAPI 镜像");
        source.getStyleClass().add("sidebar-footer");
        Label license = new Label("GPL-3.0 · v" + APP_VERSION);
        license.getStyleClass().add("sidebar-license");

        sidebar.getChildren().addAll(section, download, settings, about, spacer, source, license);
        return sidebar;
    }

    private ToggleButton navButton(String text, int index) {
        ToggleButton button = new ToggleButton(text);
        button.setToggleGroup(navGroup);
        button.getStyleClass().add("nav-button");
        button.setMaxWidth(Double.MAX_VALUE);
        button.setAlignment(Pos.CENTER_LEFT);
        button.setOnAction(event -> {
            button.setSelected(true);
            showPane(index);
        });
        navButtons.add(button);
        return button;
    }

    private void showPane(int index) {
        Node[] panes = {downloadPane, settingsPane, aboutPane};
        for (int i = 0; i < panes.length; i++) {
            Node pane = panes[i];
            if (pane == null) {
                continue;
            }
            pane.setVisible(i == index);
            pane.setManaged(i == index);
        }
        if (index < navButtons.size()) {
            navButtons.get(index).setSelected(true);
        }
    }

    // ------------------------------------------------------------------ download page

    private VBox buildDownloadPane() {
        Label title = new Label("版本库");
        title.getStyleClass().add("page-title");
        Label subtitle = new Label("选择任意正式版或快照版，一键补全 client、libraries 与全部 assets 文件");
        subtitle.getStyleClass().add("page-subtitle");
        VBox heading = new VBox(4, title, subtitle);

        searchField.setPromptText("搜索版本号，例如 1.20.1");
        searchField.getStyleClass().add("search-field");
        HBox.setHgrow(searchField, Priority.ALWAYS);
        searchField.textProperty().addListener((observable, oldValue, newValue) -> applyFilter());

        filterBox.getItems().addAll("全部版本", "正式版", "快照版", "旧版本");
        filterBox.getSelectionModel().selectFirst();
        filterBox.getStyleClass().add("filter-box");
        filterBox.setPrefWidth(136);
        filterBox.valueProperty().addListener((observable, oldValue, newValue) -> applyFilter());

        refreshButton.getStyleClass().add("button-ghost");
        refreshButton.setOnAction(event -> loadManifestAsync(true));
        versionCountLabel.getStyleClass().add("toolbar-count");

        HBox toolbar = new HBox(10, searchField, filterBox, refreshButton, versionCountLabel);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        setupTable();
        buildLoadingPane();
        StackPane tableCard = new StackPane(versionTable, loadingPane);
        tableCard.getStyleClass().add("card");
        VBox.setVgrow(tableCard, Priority.ALWAYS);

        Label dirLabel = new Label("安装目录（.minecraft）");
        dirLabel.getStyleClass().add("form-label");
        dirField.setText(prefs.get("downloadDir", AppPaths.defaultMinecraftDir().toString()));
        dirField.setTooltip(new Tooltip("游戏文件会按 versions / libraries / assets 结构写入此目录"));
        HBox.setHgrow(dirField, Priority.ALWAYS);
        dirField.textProperty().addListener((observable, oldValue, newValue) ->
                prefs.put("downloadDir", newValue == null ? "" : newValue.trim()));
        browseButton.getStyleClass().add("button-ghost");
        browseButton.setOnAction(event -> chooseDirectory());
        HBox dirRow = new HBox(8, dirField, browseButton);
        HBox.setHgrow(dirField, Priority.ALWAYS);
        VBox dirBox = new VBox(6, dirLabel, dirRow);
        HBox.setHgrow(dirBox, Priority.ALWAYS);

        startButton.getStyleClass().add("button-primary");
        startButton.setDisable(true);
        startButton.setOnAction(event -> startSelectedDownload());
        cancelButton.getStyleClass().add("button-danger");
        cancelButton.setDisable(true);
        cancelButton.setOnAction(event -> cancelDownload());
        statusLabel.getStyleClass().add("hint");
        VBox actionBox = new VBox(8, new HBox(10, startButton, cancelButton), statusLabel);
        actionBox.setAlignment(Pos.CENTER_RIGHT);

        HBox actionCard = new HBox(18, dirBox, actionBox);
        actionCard.setAlignment(Pos.CENTER_LEFT);
        actionCard.getStyleClass().addAll("card", "action-card");

        buildProgressCard();

        browseBlock.getChildren().addAll(toolbar, tableCard);
        VBox.setVgrow(tableCard, Priority.ALWAYS);

        VBox page = new VBox(16, heading, browseBlock, actionCard, progressCard);
        page.getStyleClass().add("page");
        return page;
    }

    private void setupTable() {
        versionTable.getStyleClass().add("version-table");
        versionTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        versionTable.getSelectionModel().setSelectionMode(javafx.scene.control.SelectionMode.SINGLE);
        versionTable.setPlaceholder(new Label("暂无版本数据，点击“刷新清单”重试"));
        versionTable.getSelectionModel().selectedItemProperty()
                .addListener((observable, oldValue, newValue) -> updateStartState());
        versionTable.setRowFactory(view -> {
            TableRow<VersionInfo> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && !row.isEmpty() && !busy) {
                    startSelectedDownload();
                }
            });
            return row;
        });

        TableColumn<VersionInfo, String> idColumn = new TableColumn<>("版本号");
        idColumn.setCellValueFactory(data -> new ReadOnlyStringWrapper(data.getValue().id()));
        idColumn.setPrefWidth(190);
        idColumn.setMinWidth(140);
        idColumn.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(String id, boolean empty) {
                super.updateItem(id, empty);
                if (empty || id == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                Label name = new Label(id);
                name.getStyleClass().add("version-id");
                HBox box = new HBox(8, name);
                box.setAlignment(Pos.CENTER_LEFT);
                VersionManifest current = manifest;
                if (current != null && id.equals(current.latestRelease())) {
                    Label badge = new Label("最新正式版");
                    badge.getStyleClass().addAll("badge", "badge-latest");
                    box.getChildren().add(badge);
                } else if (current != null && id.equals(current.latestSnapshot())) {
                    Label badge = new Label("最新快照");
                    badge.getStyleClass().addAll("badge", "badge-snapshot");
                    box.getChildren().add(badge);
                }
                setGraphic(box);
                setText(null);
            }
        });

        TableColumn<VersionInfo, VersionInfo> typeColumn = new TableColumn<>("类型");
        typeColumn.setCellValueFactory(data -> new ReadOnlyObjectWrapper<>(data.getValue()));
        typeColumn.setPrefWidth(120);
        typeColumn.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(VersionInfo version, boolean empty) {
                super.updateItem(version, empty);
                if (empty || version == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                Label badge = new Label(version.typeLabel());
                badge.getStyleClass().addAll("badge", version.isRelease() ? "badge-release"
                        : version.isSnapshot() ? "badge-snapshot" : "badge-old");
                setGraphic(badge);
                setText(null);
            }
        });

        TableColumn<VersionInfo, VersionInfo> timeColumn = new TableColumn<>("发布时间");
        timeColumn.setCellValueFactory(data -> new ReadOnlyObjectWrapper<>(data.getValue()));
        timeColumn.setPrefWidth(190);
        timeColumn.setComparator(Comparator.comparingLong(VersionInfo::releaseEpochMillis));
        timeColumn.setSortType(TableColumn.SortType.DESCENDING);
        timeColumn.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(VersionInfo version, boolean empty) {
                super.updateItem(version, empty);
                if (empty || version == null) {
                    setText(null);
                } else {
                    setText(version.releaseTimeText());
                }
            }
        });

        versionTable.getColumns().addAll(idColumn, typeColumn, timeColumn);

        SortedList<VersionInfo> sorted = new SortedList<>(filteredVersions);
        sorted.comparatorProperty().bind(versionTable.comparatorProperty());
        versionTable.setItems(sorted);
        versionTable.getSortOrder().add(timeColumn);
    }

    private void buildLoadingPane() {
        loadingIndicator.setPrefSize(36, 36);
        loadingIndicator.getStyleClass().add("loading-indicator");
        loadingLabel.getStyleClass().add("loading-label");
        loadingLabel.setWrapText(true);
        loadingLabel.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        VBox box = new VBox(14, loadingIndicator, loadingLabel);
        box.setAlignment(Pos.CENTER);
        box.setMaxWidth(460);
        loadingPane.getChildren().add(box);
        loadingPane.getStyleClass().add("loading-overlay");
        loadingPane.setVisible(true);
    }

    private void buildProgressCard() {
        progressCard.getStyleClass().addAll("card", "progress-card");
        progressCard.setVisible(false);
        progressCard.setManaged(false);

        Label heading = new Label("下载进度");
        heading.getStyleClass().add("card-title");
        percentLabel.getStyleClass().add("progress-percent");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox titleRow = new HBox(10, heading, spacer, percentLabel);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setPrefHeight(10);
        progressBar.getStyleClass().add("progress-bar");

        currentFileLabel.getStyleClass().add("file-label");
        currentFileLabel.setMaxWidth(Double.MAX_VALUE);

        HBox stats = new HBox(26,
                statChip("速度", speedLabel),
                statChip("已下载", sizeLabel),
                statChip("剩余时间", etaLabel),
                statChip("文件", filesLabel),
                statChip("失败", failedLabel));

        phaseLabel.getStyleClass().add("phase-label");

        progressCard.getChildren().addAll(titleRow, progressBar, currentFileLabel, stats, phaseLabel);
    }

    private Node statChip(String caption, Label value) {
        Label key = new Label(caption);
        key.getStyleClass().add("stat-caption");
        value.getStyleClass().add("stat-value");
        VBox box = new VBox(2, key, value);
        box.getStyleClass().add("stat");
        return box;
    }

    private void applyFilter() {
        String query = searchField.getText() == null ? "" : searchField.getText().trim().toLowerCase(Locale.ROOT);
        String filter = filterBox.getValue();
        filteredVersions.setPredicate(version -> {
            if (version.id() == null) {
                return false;
            }
            if (!query.isEmpty() && !version.id().toLowerCase(Locale.ROOT).contains(query)) {
                return false;
            }
            if ("正式版".equals(filter)) {
                return version.isRelease();
            }
            if ("快照版".equals(filter)) {
                return version.isSnapshot();
            }
            if ("旧版本".equals(filter)) {
                return !version.isRelease() && !version.isSnapshot();
            }
            return true;
        });
    }

    private void loadManifestAsync(boolean force) {
        if (manifestLoading) {
            return;
        }
        manifestLoading = true;
        setManifestLoading(true);
        mirrorStatusLabel.setText("● 获取清单…");
        mirrorStatusLabel.getStyleClass().remove("pill-error");
        Thread.ofVirtual().name("manifest-loader").start(() -> {
            try {
                VersionManifest loaded = downloader.loadManifest(force, ProgressListener.NOOP);
                Platform.runLater(() -> applyManifest(loaded));
            } catch (Throwable throwable) {
                Log.error("加载版本清单失败", throwable);
                Platform.runLater(() -> onManifestError(throwable));
            }
        });
    }

    private void applyManifest(VersionManifest loaded) {
        manifestLoading = false;
        manifest = loaded;
        versions.setAll(loaded.versions());
        versionCountLabel.setText("共 " + loaded.versions().size() + " 个版本");
        mirrorStatusLabel.setText("● 镜像已连接");
        mirrorStatusLabel.getStyleClass().remove("pill-error");
        setManifestLoading(false);
        refreshButton.setDisable(false);
        if (!loaded.versions().isEmpty()) {
            String id = loaded.latestRelease() != null ? loaded.latestRelease() : loaded.versions().get(0).id();
            loaded.find(id).ifPresent(version -> {
                versionTable.getSelectionModel().select(version);
                versionTable.scrollTo(version);
            });
        }
        maybeAutoStart(loaded);
    }

    /**
     * Optional automation hook: {@code -Dsnowmcd.startVersion=<id>} starts that
     * version once the manifest is ready (used for demos and smoke tests).
     */
    private void maybeAutoStart(VersionManifest loaded) {
        if (autoStartConsumed) {
            return;
        }
        autoStartConsumed = true;
        String id = System.getProperty("snowmcd.startVersion");
        if (id == null || id.isBlank()) {
            return;
        }
        loaded.find(id.trim()).ifPresent(version -> {
            versionTable.getSelectionModel().select(version);
            Platform.runLater(this::startSelectedDownload);
        });
    }

    private void onManifestError(Throwable throwable) {
        manifestLoading = false;
        mirrorStatusLabel.setText("● 镜像连接失败");
        if (!mirrorStatusLabel.getStyleClass().contains("pill-error")) {
            mirrorStatusLabel.getStyleClass().add("pill-error");
        }
        loadingLabel.setText("加载版本清单失败：" + shortMessage(throwable)
                + "\n请检查网络后点击“刷新清单”重试");
        loadingIndicator.setVisible(false);
        loadingPane.setVisible(true);
        refreshButton.setDisable(false);
    }

    private void setManifestLoading(boolean loading) {
        loadingPane.setVisible(loading);
        loadingIndicator.setVisible(loading);
        if (loading) {
            loadingLabel.setText("正在从 BMCLAPI 获取版本清单…");
        }
        refreshButton.setDisable(loading);
    }

    private void updateStartState() {
        boolean hasSelection = versionTable.getSelectionModel().getSelectedItem() != null;
        startButton.setDisable(busy || !hasSelection);
    }

    private void chooseDirectory() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("选择 Minecraft 安装目录");
        File current = currentDirectory();
        if (current != null && current.isDirectory()) {
            chooser.setInitialDirectory(current);
        }
        File selected = chooser.showDialog(stage);
        if (selected != null) {
            dirField.setText(selected.getAbsolutePath());
            settingsDirField.setText(selected.getAbsolutePath());
        }
    }

    private File currentDirectory() {
        String text = dirField.getText();
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Paths.get(text.trim()).toFile();
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ downloading

    private void startSelectedDownload() {
        VersionInfo version = versionTable.getSelectionModel().getSelectedItem();
        if (version == null || busy) {
            return;
        }
        String raw = dirField.getText() == null ? "" : dirField.getText().trim();
        if (raw.isEmpty()) {
            showAlert(Alert.AlertType.WARNING, "请选择安装目录", "请先指定一个 .minecraft 目录。");
            return;
        }
        Path directory;
        try {
            directory = Paths.get(raw).toAbsolutePath().normalize();
            Files.createDirectories(directory);
        } catch (RuntimeException | IOException e) {
            showAlert(Alert.AlertType.ERROR, "目录不可用", String.valueOf(e.getMessage()));
            return;
        }
        prefs.put("downloadDir", directory.toString());
        beginDownload(version, directory);
    }

    private void beginDownload(VersionInfo version, Path directory) {
        busy = true;
        lastReport = null;
        updateStartState();
        setDownloadingUi(true);
        browseBlock.setVisible(false);
        browseBlock.setManaged(false);
        progressCard.setVisible(true);
        progressCard.setManaged(true);
        statusLabel.setText("正在下载 " + version.id() + " …");
        progressBar.setProgress(0);
        percentLabel.setText("0%");
        currentFileLabel.setText("准备中…");
        phaseLabel.setText("正在准备安装计划…");
        speedLabel.setText("—");
        sizeLabel.setText("—");
        etaLabel.setText("—");
        filesLabel.setText("0 / 0");
        failedLabel.setText("0");

        int concurrency = concurrencySpinner.getValue();
        int retries = retriesSpinner.getValue();
        ProgressListener listener = new UiListener();
        Thread.ofVirtual().name("download-" + version.id()).start(() -> {
            try {
                DownloadReport report = downloader.download(version.id(), directory, concurrency, retries, listener);
                Platform.runLater(() -> onDownloadCompleted(version, directory, report));
            } catch (Throwable throwable) {
                Log.error("下载失败", throwable);
                Platform.runLater(() -> onDownloadError(throwable));
            }
        });
    }

    private void onDownloadCompleted(VersionInfo version, Path directory, DownloadReport report) {
        lastReport = report;
        busy = false;
        setDownloadingUi(false);
        updateStartState();
        browseBlock.setVisible(true);
        browseBlock.setManaged(true);
        progressCard.setVisible(false);
        progressCard.setManaged(false);
        if (report.cancelled()) {
            statusLabel.setText("已取消；重新开始时会跳过已完成文件");
            return;
        }
        if (report.failedFiles() > 0) {
            statusLabel.setText(report.failedFiles() + " 个文件失败，可在弹窗中仅重试失败项");
            showFailureDialog(version, directory, report);
        } else {
            statusLabel.setText("完成 " + version.id() + " · " + report.totalFiles() + " 个文件 · 用时 "
                    + formatDuration(report.elapsedMillis() / 1000));
            showSuccessDialog(version, directory, report);
        }
    }

    private void onDownloadError(Throwable throwable) {
        busy = false;
        setDownloadingUi(false);
        updateStartState();
        browseBlock.setVisible(true);
        browseBlock.setManaged(true);
        progressCard.setVisible(false);
        progressCard.setManaged(false);
        statusLabel.setText("下载出错：" + shortMessage(throwable));
        showAlert(Alert.AlertType.ERROR, "下载失败", shortMessage(throwable));
    }

    private void cancelDownload() {
        if (!busy) {
            return;
        }
        cancelButton.setDisable(true);
        phaseLabel.setText("正在取消…");
        statusLabel.setText("正在取消…");
        downloader.cancel();
    }

    private void setDownloadingUi(boolean downloading) {
        versionTable.setDisable(downloading);
        searchField.setDisable(downloading);
        filterBox.setDisable(downloading);
        refreshButton.setDisable(downloading);
        dirField.setDisable(downloading);
        browseButton.setDisable(downloading);
        startButton.setDisable(downloading || versionTable.getSelectionModel().getSelectedItem() == null);
        cancelButton.setDisable(!downloading);
    }

    private void applyProgress(ProgressSnapshot snapshot) {
        double fraction = snapshot.fraction();
        progressBar.setProgress(fraction);
        percentLabel.setText(String.format(Locale.ROOT, "%.1f%%", fraction * 100));
        String current = snapshot.currentFile();
        currentFileLabel.setText(current == null || current.isBlank() ? "准备中…" : ellipsis(current, 96));
        speedLabel.setText(snapshot.bytesPerSecond() > 1
                ? formatBytes((long) snapshot.bytesPerSecond()) + "/s" : "—");
        sizeLabel.setText(formatBytes(snapshot.downloadedBytes()) + " / " + formatBytes(snapshot.totalBytes()));
        etaLabel.setText(snapshot.etaSeconds() < 0 ? "计算中…" : formatDuration(snapshot.etaSeconds()));
        filesLabel.setText(snapshot.completedFiles() + " / " + snapshot.totalFiles());
        failedLabel.setText(String.valueOf(snapshot.failedFiles()));
    }

    private final class UiListener implements ProgressListener {
        @Override
        public void onStage(String message) {
            Platform.runLater(() -> phaseLabel.setText(message));
        }

        @Override
        public void onPlan(DownloadPlan plan) {
            Platform.runLater(() -> {
                filesLabel.setText("0 / " + plan.totalFiles());
                sizeLabel.setText("0 B / " + formatBytes(plan.totalBytes()));
            });
        }

        @Override
        public void onProgress(ProgressSnapshot snapshot) {
            Platform.runLater(() -> applyProgress(snapshot));
        }

        @Override
        public void onFileFailed(DownloadTask task, String error) {
            Log.warning("文件下载失败 %s: %s", task.label(), error);
        }

        @Override
        public void onLog(String message) {
            Log.info("%s", message);
        }
    }

    // ------------------------------------------------------------------ dialogs

    private void showSuccessDialog(VersionInfo version, Path directory, DownloadReport report) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(stage);
        dialog.setTitle("下载完成");
        ButtonType open = new ButtonType("打开版本目录", ButtonBar.ButtonData.OK_DONE);
        ButtonType close = new ButtonType("关闭", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(open, close);
        styleDialog(dialog);

        Label icon = new Label("✓");
        icon.getStyleClass().add("dialog-icon-success");
        Label heading = new Label("下载完成");
        heading.getStyleClass().add("dialog-title");
        HBox titleRow = new HBox(12, icon, heading);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        Label message = new Label("版本 " + version.id() + " 已安装到：\n" + directory
                + "\n可被 HMCL、PCL2 或官方启动器直接识别。");
        message.setWrapText(true);
        message.getStyleClass().add("dialog-message");

        HBox stats = new HBox(24,
                dialogStat("文件总数", report.totalFiles() + " 个"),
                dialogStat("本次下载", report.succeededFiles() + " 个"),
                dialogStat("已存在跳过", report.skippedFiles() + " 个"),
                dialogStat("用时", formatDuration(report.elapsedMillis() / 1000)));

        VBox body = new VBox(14, titleRow, message, stats);
        dialog.getDialogPane().setContent(body);
        dialog.getDialogPane().setPrefWidth(560);

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isPresent() && result.get() == open) {
            openFolder(directory.resolve("versions").resolve(version.id()));
        }
    }

    private void showFailureDialog(VersionInfo version, Path directory, DownloadReport report) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(stage);
        dialog.setTitle("部分文件下载失败");
        ButtonType retry = new ButtonType("仅重试失败项", ButtonBar.ButtonData.OK_DONE);
        ButtonType close = new ButtonType("关闭", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(retry, close);
        styleDialog(dialog);

        Label icon = new Label("!");
        icon.getStyleClass().add("dialog-icon-error");
        Label heading = new Label(report.failedFiles() + " 个文件下载失败");
        heading.getStyleClass().add("dialog-title");
        HBox titleRow = new HBox(12, icon, heading);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        Label message = new Label("每个文件已自动重试，仍然失败的文件列在下面。"
                + "可以仅重试这些文件，已成功的文件不会重复下载。");
        message.setWrapText(true);
        message.getStyleClass().add("dialog-message");

        TextArea list = new TextArea();
        list.setEditable(false);
        list.setPrefRowCount(9);
        list.getStyleClass().add("failure-list");
        StringBuilder text = new StringBuilder();
        for (FailedDownload failure : report.failures()) {
            text.append("• ").append(failure.task().label()).append('\n')
                    .append("    ").append(failure.error()).append('\n')
                    .append("    ").append(failure.task().url()).append("\n\n");
        }
        list.setText(text.toString());

        VBox body = new VBox(12, titleRow, message, list);
        dialog.getDialogPane().setContent(body);
        dialog.getDialogPane().setPrefWidth(660);

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isPresent() && result.get() == retry) {
            retryFailed(version, directory, report);
        }
    }

    private void retryFailed(VersionInfo version, Path directory, DownloadReport report) {
        if (busy || report.failures().isEmpty()) {
            return;
        }
        busy = true;
        updateStartState();
        setDownloadingUi(true);
        browseBlock.setVisible(false);
        browseBlock.setManaged(false);
        progressCard.setVisible(true);
        progressCard.setManaged(true);
        statusLabel.setText("正在重试 " + report.failures().size() + " 个失败文件…");
        progressBar.setProgress(0);
        percentLabel.setText("0%");
        phaseLabel.setText("重试失败文件…");
        currentFileLabel.setText("准备中…");
        speedLabel.setText("—");
        etaLabel.setText("—");
        filesLabel.setText("0 / " + report.failures().size());

        ProgressListener listener = new UiListener();
        int concurrency = concurrencySpinner.getValue();
        int retries = retriesSpinner.getValue();
        Thread.ofVirtual().name("download-retry").start(() -> {
            try {
                DownloadReport retried = downloader.retry(report, directory, version.id(), concurrency, retries, listener);
                Platform.runLater(() -> onDownloadCompleted(version, directory, retried));
            } catch (Throwable throwable) {
                Log.error("重试失败", throwable);
                Platform.runLater(() -> onDownloadError(throwable));
            }
        });
    }

    private void openFolder(Path path) {
        Path target = Files.isDirectory(path) ? path : path.getParent();
        if (target == null) {
            return;
        }
        try {
            new ProcessBuilder("explorer.exe", target.toString()).start();
        } catch (IOException e) {
            showAlert(Alert.AlertType.INFORMATION, "无法打开目录", target.toString());
        }
    }

    private void showAlert(Alert.AlertType type, String title, String message) {
        Alert alert = new Alert(type);
        alert.initOwner(stage);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.getDialogPane().getStylesheets().add(CSS);
        alert.getDialogPane().getStyleClass().add("snow-dialog");
        alert.getDialogPane().setPrefWidth(520);
        alert.showAndWait();
    }

    private void styleDialog(Dialog<?> dialog) {
        dialog.getDialogPane().getStylesheets().add(CSS);
        dialog.getDialogPane().getStyleClass().add("snow-dialog");
    }

    private Node dialogStat(String caption, String value) {
        Label key = new Label(caption);
        key.getStyleClass().add("stat-caption");
        Label data = new Label(value);
        data.getStyleClass().add("stat-value");
        return new VBox(2, key, data);
    }

    // ------------------------------------------------------------------ settings / about

    private VBox buildSettingsPane() {
        Label title = new Label("设置");
        title.getStyleClass().add("page-title");
        Label subtitle = new Label("调整下载行为；修改会立即保存并在下次下载时生效");
        subtitle.getStyleClass().add("page-subtitle");
        VBox heading = new VBox(4, title, subtitle);

        int savedConcurrency = prefs.getInt("concurrency", 32);
        concurrencySpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(
                1, 128, Math.clamp(savedConcurrency, 1, 128)));
        concurrencySpinner.setPrefWidth(120);
        concurrencySpinner.setEditable(false);
        concurrencySpinner.valueProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null) {
                prefs.putInt("concurrency", newValue);
            }
        });

        int savedRetries = prefs.getInt("retries", 3);
        retriesSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(
                0, 10, Math.clamp(savedRetries, 0, 10)));
        retriesSpinner.setPrefWidth(120);
        retriesSpinner.setEditable(false);
        retriesSpinner.valueProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null) {
                prefs.putInt("retries", newValue);
            }
        });

        GridPane grid = new GridPane();
        grid.setHgap(18);
        grid.setVgap(16);
        grid.add(settingLabel("最大并发下载数", "同时下载的文件数量，默认 32"), 0, 0);
        grid.add(concurrencySpinner, 1, 0);
        grid.add(settingLabel("单文件重试次数", "失败后按 1s / 2s / 4s 指数退避重试"), 0, 1);
        grid.add(retriesSpinner, 1, 1);
        VBox downloadCard = new VBox(16, cardHeading("下载"), grid);
        downloadCard.getStyleClass().addAll("card", "settings-card");

        settingsDirField.setText(prefs.get("downloadDir", AppPaths.defaultMinecraftDir().toString()));
        HBox.setHgrow(settingsDirField, Priority.ALWAYS);
        Button settingsBrowse = new Button("浏览…");
        settingsBrowse.getStyleClass().add("button-ghost");
        settingsBrowse.setOnAction(event -> {
            File selected = chooseDirectoryForSettings();
            if (selected != null) {
                settingsDirField.setText(selected.getAbsolutePath());
            }
        });
        settingsDirField.textProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue == null || newValue.isBlank()) {
                return;
            }
            prefs.put("downloadDir", newValue.trim());
            dirField.setText(newValue.trim());
        });
        HBox dirRow = new HBox(8, settingsDirField, settingsBrowse);
        HBox.setHgrow(settingsDirField, Priority.ALWAYS);
        VBox dirBox = new VBox(6, settingLabel("默认安装目录", "默认使用 %APPDATA%\\.minecraft"), dirRow);

        Path logFile = Log.logFile();
        Label logPath = new Label(logFile == null ? "日志文件不可用" : logFile.toString());
        logPath.getStyleClass().add("code-label");
        logPath.setWrapText(true);
        Button openLogs = new Button("打开日志目录");
        openLogs.getStyleClass().add("button-ghost");
        openLogs.setOnAction(event -> openFolder(AppPaths.logsDir()));
        Button clearCache = new Button("清除版本清单缓存");
        clearCache.getStyleClass().add("button-ghost");
        clearCache.setOnAction(event -> clearCache(clearCache));
        HBox logActions = new HBox(8, openLogs, clearCache);

        VBox storageCard = new VBox(14, cardHeading("目录与日志"), dirBox, logPath, logActions);
        storageCard.getStyleClass().addAll("card", "settings-card");

        Label mirror = new Label(MirrorInjector.MIRROR_BASE);
        mirror.getStyleClass().add("code-label");
        Label mirrorNote = new Label("所有版本元数据、客户端、依赖库与资源文件均从 BMCLAPI 镜像获取；"
                + "路径会保留官方的 /v1 等分段，避免镜像 404。");
        mirrorNote.getStyleClass().add("hint");
        mirrorNote.setWrapText(true);
        VBox mirrorCard = new VBox(12, cardHeading("下载源"), mirror, mirrorNote);
        mirrorCard.getStyleClass().addAll("card", "settings-card");

        VBox page = new VBox(16, heading, downloadCard, storageCard, mirrorCard);
        page.getStyleClass().add("page");
        ScrollPane scroll = new ScrollPane(page);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("page-scroll");
        VBox wrapper = new VBox(scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        return wrapper;
    }

    private File chooseDirectoryForSettings() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("选择默认安装目录");
        try {
            Path path = Paths.get(settingsDirField.getText());
            if (Files.isDirectory(path)) {
                chooser.setInitialDirectory(path.toFile());
            }
        } catch (RuntimeException ignored) {
            // keep default
        }
        return chooser.showDialog(stage);
    }

    private Node settingLabel(String title, String hint) {
        Label label = new Label(title);
        label.getStyleClass().add("form-label");
        Label sub = new Label(hint);
        sub.getStyleClass().add("hint");
        return new VBox(3, label, sub);
    }

    private Label cardHeading(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("card-title");
        return label;
    }

    private void clearCache(Button button) {
        Path cache = AppPaths.cacheDir().resolve("version_manifest_v2.json");
        try {
            boolean deleted = Files.deleteIfExists(cache);
            button.setText(deleted ? "缓存已清除" : "没有缓存");
        } catch (IOException e) {
            button.setText("清除失败");
        }
    }

    private VBox buildAboutPane() {
        Label title = new Label("关于 SnowMCD");
        title.getStyleClass().add("page-title");
        Label subtitle = new Label("把完整的 Minecraft 安装写进 .minecraft，交给任意启动器启动");
        subtitle.getStyleClass().add("page-subtitle");
        VBox heading = new VBox(4, title, subtitle);

        Label description = new Label("""
                SnowMCD 是一个开源的 Minecraft 全量下载器。它会根据版本清单安装
                client.jar、全部依赖库（含 LWJGL 原生库）以及所有资源文件，
                生成与官方启动器完全一致的标准 .minecraft 目录结构。
                下载完成后，HMCL、PCL2 或官方启动器可以直接识别这个目录。""");
        description.setWrapText(true);
        description.getStyleClass().add("about-text");

        VBox featureCard = new VBox(10, cardHeading("特性"),
                bullet("完整安装：versions / libraries / assets 一次性补齐"),
                bullet("SHA-1 校验：每个文件逐一校验，失败自动重试 3 次"),
                bullet("虚拟线程并发：默认 32 并发，可调；支持取消"),
                bullet("缓存命中：已存在且校验通过的文件直接跳过"),
                bullet("BMCLAPI 镜像：保留 /v1 路径段，版本清单本地缓存"),
                bullet("标准目录：无需修改启动器，也无需额外补文件"));
        featureCard.getStyleClass().addAll("card", "settings-card");

        GridPane metadata = new GridPane();
        metadata.setHgap(24);
        metadata.setVgap(10);
        metadata.add(metaLabel("版本"), 0, 0);
        metadata.add(metaValue(APP_VERSION), 1, 0);
        metadata.add(metaLabel("运行时"), 0, 1);
        metadata.add(metaValue("JDK " + System.getProperty("java.version") + " · JavaFX 25"), 1, 1);
        metadata.add(metaLabel("许可证"), 0, 2);
        metadata.add(metaValue("GPL-3.0"), 1, 2);
        metadata.add(metaLabel("下载源"), 0, 3);
        metadata.add(metaValue("BMCLAPI (bmclapi2.bangbang93.com)"), 1, 3);
        metadata.add(metaLabel("日志"), 0, 4);
        metadata.add(metaValue(Log.logFile() == null ? "—" : Log.logFile().toString()), 1, 4);
        VBox metadataCard = new VBox(14, cardHeading("信息"), metadata);
        metadataCard.getStyleClass().addAll("card", "settings-card");

        Label disclaimer = new Label("本工具仅下载 Mojang 官方发布的游戏文件，不包含游戏本体；"
                + "Minecraft 是 Mojang Studios 的商标。镜像服务由 BMCLAPI 社区提供。");
        disclaimer.setWrapText(true);
        disclaimer.getStyleClass().add("hint");

        VBox page = new VBox(16, heading, description, featureCard, metadataCard, disclaimer);
        page.getStyleClass().add("page");
        ScrollPane scroll = new ScrollPane(page);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("page-scroll");
        VBox wrapper = new VBox(scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        wrapper.setMaxWidth(Double.MAX_VALUE);
        return wrapper;
    }

    private Node bullet(String text) {
        Label label = new Label("•  " + text);
        label.getStyleClass().add("bullet");
        label.setWrapText(true);
        return label;
    }

    private Label metaLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("meta-key");
        return label;
    }

    private Label metaValue(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("meta-value");
        label.setWrapText(true);
        return label;
    }

    private void onCloseRequest(WindowEvent event) {
        if (!busy) {
            return;
        }
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                "正在下载，退出会中断当前任务。确定退出吗？",
                ButtonType.YES, ButtonType.NO);
        alert.initOwner(stage);
        alert.setTitle("确认退出");
        alert.setHeaderText(null);
        alert.getDialogPane().getStylesheets().add(CSS);
        alert.getDialogPane().getStyleClass().add("snow-dialog");
        ButtonType choice = alert.showAndWait().orElse(ButtonType.NO);
        if (choice != ButtonType.YES) {
            event.consume();
            return;
        }
        downloader.cancel();
    }

    // ------------------------------------------------------------------ formatting

    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes;
        String[] units = {"KB", "MB", "GB", "TB"};
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.ROOT, value >= 100 ? "%.0f %s" : "%.1f %s", value, units[unit]);
    }

    private static String formatDuration(long seconds) {
        if (seconds < 0) {
            return "—";
        }
        if (seconds < 60) {
            return seconds + " 秒";
        }
        long minutes = seconds / 60;
        long rest = seconds % 60;
        if (minutes < 60) {
            return minutes + " 分 " + rest + " 秒";
        }
        return (minutes / 60) + " 小时 " + (minutes % 60) + " 分";
    }

    private static String ellipsis(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : "…" + text.substring(text.length() - max + 1);
    }

    private static String shortMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            message = throwable.getClass().getSimpleName();
        }
        return message.length() > 300 ? message.substring(0, 300) + "…" : message;
    }
}
