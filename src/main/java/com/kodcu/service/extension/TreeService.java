package com.kodcu.service.extension;

import com.kodcu.controller.ApplicationController;
import com.kodcu.other.Current;
import com.kodcu.other.IOHelper;
import com.kodcu.other.TrimWhite;
import com.kodcu.other.Tuple;
import com.kodcu.service.ThreadService;
import com.kodcu.service.cache.BinaryCacheService;
import com.kodcu.service.ui.AwesomeService;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.image.WritableImage;
import javafx.scene.web.WebView;
import netscape.javascript.JSObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Created by usta on 25.12.2014.
 */
@Component
public class TreeService implements DefaultSettings {

    private final Logger logger = LoggerFactory.getLogger(TreeService.class);

    private Current current;
    private ApplicationController controller;
    private ThreadService threadService;
    private AwesomeService awesomeService;
    private final BinaryCacheService binaryCacheService;

    Pattern pattern = Pattern.compile("^(addw|minw|setw|addh|minh|seth|scale):\\s*(\\d+)$");

    @Value("${application.treeview.url}")
    private String treeviewUrl;
    private WebView treeview;
    private boolean initialized;

    @Autowired
    public TreeService(final Current current, final ApplicationController controller, final ThreadService threadService,
                       final AwesomeService awesomeService, BinaryCacheService binaryCacheService) {
        this.current = current;
        this.controller = controller;
        this.threadService = threadService;
        this.awesomeService = awesomeService;
        this.binaryCacheService = binaryCacheService;
    }

    public void createFileTree(String tree, String type, String imagesDir, String imageTarget, String nodename) {

        Objects.requireNonNull(imageTarget);

        boolean cachedResource = imageTarget.contains("/afx/cache");

        if (!imageTarget.endsWith(".png") && !cachedResource)
            return;

        Integer cacheHit = current.getCache().get(imageTarget);

        int hashCode = (imageTarget + imagesDir + type + tree).hashCode();
        if (Objects.isNull(cacheHit) || hashCode != cacheHit) {

            logger.debug("Tree extension is started for {}", imageTarget);

            try {
                List<String> strings = Arrays.asList(tree.split("\\r?\\n"));

                Map<String, Integer> settings = newSettings();

                List<TreeItem<Tuple<Integer, String>>> treeItems = strings.stream()
                        .map(s -> {

                            if (s.isEmpty()) {
                                return null;
                            }

                            Matcher matcher = pattern.matcher(s);
                            if (matcher.matches() && matcher.groupCount() == 2) {
                                String command = matcher.group(1);
                                String value = matcher.group(2);

                                settings.put(command, Integer.valueOf(value));
                                return null;
                            }

                            if (!s.contains("#")) {
                                return null;
                            }

                            int level = StringUtils.countOccurrencesOf(s, "#");
                            String value = s.replace(" ", "").replace("#", "");
                            return new Tuple<Integer, String>(level, value);
                        })
                        .filter(Objects::nonNull)
                        .map(t -> {
                            Node icon = awesomeService.getIcon(Paths.get(t.getValue()));
                            TreeItem<Tuple<Integer, String>> treeItem = new TreeItem<>(t, icon);
                            treeItem.setExpanded(true);

                            return treeItem;
                        })
                        .collect(Collectors.toList());

                TreeView fileView = getSnaphotTreeView();
                fileView.setScaleX(settings.get("scale"));
                fileView.setScaleY(settings.get("scale"));

                for (int index = 0; index < treeItems.size(); index++) {

                    TreeItem<Tuple<Integer, String>> currentItem = treeItems.get(index);
                    Tuple<Integer, String> currentItemValue = currentItem.getValue();

                    if (index == 0) {
                        fileView.setRoot(currentItem);
                        continue;
                    }

                    TreeItem<Tuple<Integer, String>> lastItem = treeItems.get(index - 1);
                    int lastPos = lastItem.getValue().getKey();

                    if (currentItemValue.getKey() > lastPos) {

                        lastItem.getChildren().add(currentItem);
                        continue;
                    }

                    if (currentItemValue.getKey() == lastPos) {

                        TreeItem<Tuple<Integer, String>> parent = lastItem.getParent();
                        if (Objects.isNull(parent))
                            parent = fileView.getRoot();
                        parent.getChildren().add(currentItem);
                        continue;
                    }

                    if (currentItemValue.getKey() < lastPos) {

                        List<TreeItem<Tuple<Integer, String>>> collect = treeItems.stream()
                                .filter(t -> t.getValue().getKey() == currentItemValue.getKey())
                                .collect(Collectors.toList());

                        if (collect.size() > 0) {

                            TreeItem<Tuple<Integer, String>> parent = fileView.getRoot();

                            try {
                                TreeItem<Tuple<Integer, String>> treeItem = collect.get(collect.indexOf(currentItem) - 1);
                                parent = treeItem.getParent();
                            } catch (RuntimeException e) {
                                logger.info(e.getMessage(), e);
                            }

                            parent.getChildren().add(currentItem);
                        }
                        continue;
                    }

                }

                Path path = current.currentTab().getParentOrWorkdir();

                int changeWidth = (settings.get("addw") - settings.get("minw"));
                int changeHeight = (settings.get("addh") - settings.get("minh"));

                threadService.runActionLater(() -> {
                    controller.getRootAnchor().getChildren().add(fileView);

                    if (settings.get("setw") > 0) {
                        fileView.setPrefWidth(settings.get("setw"));
                    } else {
                        fileView.setPrefWidth(300 + changeWidth);
                    }

                    if (settings.get("seth") > 0) {
                        fileView.setPrefHeight(settings.get("seth"));
                    } else {
                        fileView.setPrefHeight((treeItems.size() * 24) + 30 + changeHeight);
                    }

                    WritableImage writableImage = fileView.snapshot(new SnapshotParameters(), null);
                    BufferedImage bufferedImage = SwingFXUtils.fromFXImage(writableImage, null);

                    if (!cachedResource) {

                        Path treePath = path.resolve(imageTarget);
                        IOHelper.createDirectories(path.resolve(imagesDir));
                        IOHelper.imageWrite((BufferedImage) bufferedImage, "png", treePath.toFile());
                        controller.clearImageCache(treePath);

                    } else {
                        binaryCacheService.putBinary(imageTarget, (BufferedImage) bufferedImage);
                        controller.clearImageCache(imageTarget);
                    }

                    logger.debug("Tree extension is ended for {}", imageTarget);

                    controller.getRootAnchor().getChildren().remove(fileView);

                });

            } catch (Exception e) {
                logger.error("Problem occured while generating Filesystem Tree", e);
            }
        }

        current.getCache().put(imageTarget, hashCode);
    }

    private TreeView getSnaphotTreeView() {
        TreeView fileView = new TreeView();
        fileView.getStyleClass().add("file-tree");
        fileView.setLayoutX(-13000);
        fileView.setLayoutY(-13000);
        fileView.setMinSize(0, 0);
        return fileView;
    }

    public void createHighlightFileTree(String tree, String type, String imagesDir, String imageTarget, String nodename) {
        Objects.requireNonNull(imageTarget);

        boolean cachedResource = imageTarget.contains("/afx/cache");

        if (!imageTarget.endsWith(".png") && !cachedResource)
            return;

        Integer cacheHit = current.getCache().get(imageTarget);

        int hashCode = (imageTarget + imagesDir + type + tree).hashCode();
        if (Objects.isNull(cacheHit) || hashCode != cacheHit) {

            Path path = current.currentTab().getParentOrWorkdir();

            threadService.runActionLater(() -> {

                WebView treeview = new WebView();
                treeview.setLayoutX(-16000);
                treeview.setLayoutY(-16000);
                treeview.setMinSize(0, 0);
                treeview.setPrefSize(3000, 6000);
                treeview.setZoom(2);

                controller.getRootAnchor().getChildren().add(treeview);

                threadService.runActionLater(() -> {
                    treeview.getEngine().load(String.format(treeviewUrl, controller.getPort()));
                });

                treeview.getEngine().setOnAlert(event -> {
                    String data = event.getData();
                    if ("READY".equals(data)) {
                        ((JSObject) treeview.getEngine().executeScript("window")).call("executeTree", tree);
                    }
                    if ("RENDERED".equals(data)) {

                        WritableImage writableImage = treeview.snapshot(new SnapshotParameters(), null);
                        BufferedImage bufferedImage = SwingFXUtils.fromFXImage(writableImage, null);

                        threadService.runTaskLater(() -> {
                            TrimWhite trimWhite = new TrimWhite();
                            BufferedImage trimmed = trimWhite.trim(bufferedImage);

                            if (!cachedResource) {

                                Path treePath = path.resolve(imageTarget);
                                IOHelper.createDirectories(path.resolve(imagesDir));
                                IOHelper.imageWrite(trimmed, "png", treePath.toFile());
                                controller.clearImageCache(treePath);
                            } else {
                                binaryCacheService.putBinary(imageTarget, trimmed);
                                controller.clearImageCache(imageTarget);
                            }

                            threadService.runActionLater(() -> {
                                controller.getRootAnchor().getChildren().remove(treeview);
                            });
                        });

                    }
                });
            });

        }

        current.getCache().put(imageTarget, hashCode);
    }
}
