package com.example.abyss.debug.tweak;

import com.example.abyss.world.gen.Layer;
import com.example.abyss.world.gen.LayerGenConfig;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.text.DecimalFormat;

/**
 * A collapsible Swing panel showing sliders for every numeric knob in a
 * single layer's {@link LayerGenConfig}.
 * <p>
 * When any slider changes its value the panel immediately calls
 * {@link GenParamOverrides#setConfig(Layer, LayerGenConfig)} so the LibGDX
 * render thread picks up the change on its next frame.
 * </p>
 */
class LayerKnobPanel extends JPanel {

    // Slider integer resolution (sliders work with ints, so we multiply floats).
    private static final int SLIDER_RESOLUTION = 1000;

    // Per-knob ranges
    private static final float THRESHOLD_MIN  = -0.5f;
    private static final float THRESHOLD_MAX  =  1.0f;
    private static final float FREQUENCY_MIN  =  0.001f;
    private static final float FREQUENCY_MAX  =  0.1f;
    private static final float AMPLITUDE_MIN  =  0.1f;
    private static final float AMPLITUDE_MAX  =  2.0f;
    private static final float SHAFT_DIA_MIN  =  50f;
    private static final float SHAFT_DIA_MAX  =  800f;

    private final Layer layer;
    private final GenParamOverrides overrides;

    private final JSlider thresholdSlider;
    private final JSlider frequencySlider;
    private final JSlider amplitudeSlider;
    private final JSlider shaftDiameterSlider;

    private final JLabel thresholdLabel;
    private final JLabel frequencyLabel;
    private final JLabel amplitudeLabel;
    private final JLabel shaftDiameterLabel;

    private final DecimalFormat fmt3 = new DecimalFormat("0.000");
    private final DecimalFormat fmt1 = new DecimalFormat("0.0");

    /** Whether to suppress change callbacks while the sliders are being initialised. */
    private boolean initialising = true;

    LayerKnobPanel(Layer layer, GenParamOverrides overrides) {
        this.layer     = layer;
        this.overrides = overrides;

        LayerGenConfig cfg = overrides.getConfig(layer);

        setLayout(new GridBagLayout());
        setBorder(new TitledBorder(layer.name()));

        thresholdSlider    = buildSlider(cfg.baseDensityThreshold(), THRESHOLD_MIN,  THRESHOLD_MAX);
        frequencySlider    = buildSlider(cfg.noiseFrequency(),        FREQUENCY_MIN,  FREQUENCY_MAX);
        amplitudeSlider    = buildSlider(cfg.noiseAmplitude(),        AMPLITUDE_MIN,  AMPLITUDE_MAX);
        shaftDiameterSlider = buildSlider(cfg.shaftDiameter(),        SHAFT_DIA_MIN,  SHAFT_DIA_MAX);

        thresholdLabel    = new JLabel(fmt3.format(cfg.baseDensityThreshold()));
        frequencyLabel    = new JLabel(fmt3.format(cfg.noiseFrequency()));
        amplitudeLabel    = new JLabel(fmt3.format(cfg.noiseAmplitude()));
        shaftDiameterLabel = new JLabel(fmt1.format(cfg.shaftDiameter()));

        addRow(0, "Density Threshold", thresholdSlider,    thresholdLabel);
        addRow(1, "Noise Frequency",   frequencySlider,    frequencyLabel);
        addRow(2, "Noise Amplitude",   amplitudeSlider,    amplitudeLabel);
        addRow(3, "Shaft Diameter",    shaftDiameterSlider, shaftDiameterLabel);

        JButton resetBtn = new JButton("Reset");
        resetBtn.setToolTipText("Reset this layer to its baked defaults");
        resetBtn.addActionListener(e -> resetToDefaults());
        GridBagConstraints rbc = gbc(0, 4, 3);
        rbc.anchor = GridBagConstraints.EAST;
        rbc.fill   = GridBagConstraints.NONE;
        add(resetBtn, rbc);

        initialising = false;

        wireListeners();
    }

    // ---------------------------------------------------------------
    // Listener wiring
    // ---------------------------------------------------------------

    private void wireListeners() {
        thresholdSlider.addChangeListener(e -> onKnobChanged());
        frequencySlider.addChangeListener(e -> onKnobChanged());
        amplitudeSlider.addChangeListener(e -> onKnobChanged());
        shaftDiameterSlider.addChangeListener(e -> onKnobChanged());
    }

    private void onKnobChanged() {
        boolean suppressingChange = initialising;
        if (suppressingChange) {
            return;
        }

        float threshold  = sliderToFloat(thresholdSlider,    THRESHOLD_MIN,  THRESHOLD_MAX);
        float frequency  = sliderToFloat(frequencySlider,    FREQUENCY_MIN,  FREQUENCY_MAX);
        float amplitude  = sliderToFloat(amplitudeSlider,    AMPLITUDE_MIN,  AMPLITUDE_MAX);
        float shaftDia   = sliderToFloat(shaftDiameterSlider, SHAFT_DIA_MIN, SHAFT_DIA_MAX);

        thresholdLabel.setText(fmt3.format(threshold));
        frequencyLabel.setText(fmt3.format(frequency));
        amplitudeLabel.setText(fmt3.format(amplitude));
        shaftDiameterLabel.setText(fmt1.format(shaftDia));

        LayerGenConfig current = overrides.getConfig(layer);
        LayerGenConfig updated = new LayerGenConfig(
                threshold,
                frequency,
                amplitude,
                shaftDia,
                current.dominantVoxelTypes()
        );
        overrides.setConfig(layer, updated);
    }

    // ---------------------------------------------------------------
    // Reset
    // ---------------------------------------------------------------

    void resetToDefaults() {
        LayerGenConfig defaults = layer.getGenConfig();
        initialising = true;
        thresholdSlider.setValue(floatToSlider(defaults.baseDensityThreshold(), THRESHOLD_MIN, THRESHOLD_MAX));
        frequencySlider.setValue(floatToSlider(defaults.noiseFrequency(),       FREQUENCY_MIN, FREQUENCY_MAX));
        amplitudeSlider.setValue(floatToSlider(defaults.noiseAmplitude(),       AMPLITUDE_MIN, AMPLITUDE_MAX));
        shaftDiameterSlider.setValue(floatToSlider(defaults.shaftDiameter(),    SHAFT_DIA_MIN, SHAFT_DIA_MAX));
        thresholdLabel.setText(fmt3.format(defaults.baseDensityThreshold()));
        frequencyLabel.setText(fmt3.format(defaults.noiseFrequency()));
        amplitudeLabel.setText(fmt3.format(defaults.noiseAmplitude()));
        shaftDiameterLabel.setText(fmt1.format(defaults.shaftDiameter()));
        initialising = false;
        overrides.setConfig(layer, defaults);
    }

    // ---------------------------------------------------------------
    // Layout helpers
    // ---------------------------------------------------------------

    private void addRow(int row, String label, JSlider slider, JLabel valueLabel) {
        add(new JLabel(label), gbc(0, row, 1));
        add(slider,            gbc(1, row, 1));

        Dimension valueDim = new Dimension(52, valueLabel.getPreferredSize().height);
        valueLabel.setPreferredSize(valueDim);
        valueLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        add(valueLabel,        gbc(2, row, 1));
    }

    private static GridBagConstraints gbc(int col, int row, int colSpan) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx      = col;
        c.gridy      = row;
        c.gridwidth  = colSpan;
        c.gridheight = 1;
        c.insets     = new Insets(2, 4, 2, 4);
        c.fill       = GridBagConstraints.HORIZONTAL;
        c.weightx    = col == 1 ? 1.0 : 0.0;
        return c;
    }

    // ---------------------------------------------------------------
    // Slider ↔ float conversion
    // ---------------------------------------------------------------

    private static JSlider buildSlider(float value, float min, float max) {
        JSlider slider = new JSlider(0, SLIDER_RESOLUTION, floatToSlider(value, min, max));
        slider.setPreferredSize(new Dimension(220, slider.getPreferredSize().height));
        return slider;
    }

    private static int floatToSlider(float value, float min, float max) {
        float clamped = Math.max(min, Math.min(max, value));
        return Math.round((clamped - min) / (max - min) * SLIDER_RESOLUTION);
    }

    private static float sliderToFloat(JSlider slider, float min, float max) {
        return min + (slider.getValue() / (float) SLIDER_RESOLUTION) * (max - min);
    }
}
