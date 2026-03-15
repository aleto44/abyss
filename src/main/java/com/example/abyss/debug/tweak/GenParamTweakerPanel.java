package com.example.abyss.debug.tweak;

import com.example.abyss.world.gen.Layer;

import javax.swing.*;
import java.awt.*;
import java.util.EnumMap;
import java.util.Map;

/**
 * Swing {@code JFrame} that hosts a {@link LayerKnobPanel} for every
 * {@link Layer}.  Each panel exposes sliders for the four numeric
 * {@link com.example.abyss.world.gen.LayerGenConfig} knobs.
 *
 * <p>The frame is designed to sit alongside the LibGDX window.  Construct and
 * show it <em>before</em> creating the LibGDX application so the Swing EDT is
 * fully initialised first.</p>
 */
public class GenParamTweakerPanel {

    private final JFrame frame;
    private final GenParamOverrides overrides;
    private final Map<Layer, LayerKnobPanel> layerPanels = new EnumMap<>(Layer.class);

    public GenParamTweakerPanel(GenParamOverrides overrides) {
        this.overrides = overrides;
        this.frame     = buildFrame();
    }

    // ---------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------

    /** Makes the Swing frame visible (call from the EDT or Swing-safe context). */
    public void show() {
        SwingUtilities.invokeLater(() -> frame.setVisible(true));
    }

    /** Hides and disposes the Swing frame. */
    public void dispose() {
        SwingUtilities.invokeLater(frame::dispose);
    }

    // ---------------------------------------------------------------
    // Frame construction
    // ---------------------------------------------------------------

    private JFrame buildFrame() {
        JFrame f = new JFrame("Abyss – Gen Param Tweaker");
        f.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        f.setLayout(new BorderLayout());

        JPanel contentPanel = new JPanel();
        contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));

        for (Layer layer : Layer.values()) {
            LayerKnobPanel panel = new LayerKnobPanel(layer, overrides);
            layerPanels.put(layer, panel);
            contentPanel.add(panel);
        }

        JScrollPane scrollPane = new JScrollPane(contentPanel);
        scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);

        f.add(scrollPane, BorderLayout.CENTER);
        f.add(buildToolbar(), BorderLayout.SOUTH);

        f.setPreferredSize(new Dimension(520, 700));
        f.pack();
        f.setLocation(20, 60);

        return f;
    }

    private JPanel buildToolbar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.RIGHT));

        JButton resetAllBtn = new JButton("Reset All Layers");
        resetAllBtn.setToolTipText("Revert every layer to its baked defaults");
        resetAllBtn.addActionListener(e -> resetAllLayers());
        bar.add(resetAllBtn);

        return bar;
    }

    private void resetAllLayers() {
        overrides.resetAll();
        for (LayerKnobPanel panel : layerPanels.values()) {
            panel.resetToDefaults();
        }
    }
}
