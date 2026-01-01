package net.judahzone.scope;

import static judahzone.util.WavConstants.*;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.Toolkit;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.Closeable;
import java.io.File;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JToggleButton;

import be.tarsos.dsp.util.fft.HammingWindow;
import judahzone.api.Live;
import judahzone.api.Transform;
import judahzone.gui.Floating;
import judahzone.gui.Gui;
import judahzone.gui.Nimbus;
import judahzone.javax.JavaxIn;
import judahzone.javax.JavaxOut;
import judahzone.util.AudioMetrics;
import judahzone.util.AudioMetrics.RMS;
import judahzone.util.Constants;
import judahzone.util.FFZ;
import judahzone.util.Folders;
import judahzone.util.FromDisk;
import judahzone.util.MP3;
import judahzone.util.RTLogger;
import judahzone.util.Recording;
import judahzone.util.Services;
import judahzone.util.Threads;

/** Provides a Spectrometer, a Spectrogram and RMSmeter, listening to mixer's selected channels
 * (on a circular 20 sec buffer) or analyze an audio file from disk */
public class JudahScope extends JPanel implements Live, Floating, Closeable {

    public static enum Mode{ LIVE_ROLLING, LIVE_STOPPED, FILE }

    /** our transformer */
    public static final FFZ fft = new FFZ(FFT_SIZE, new HammingWindow());
    static final int JACK_BUFFER = Constants.bufSize();

    static final int S_RATE = Constants.sampleRate();
    private static final int MENU_HEIGHT = 32;
    private static final int VERTICAL_SPACING = 26;
    public static final Dimension SLIDER = new Dimension(60, 24);
    public static final Dimension FEEDBACK = new Dimension(190, MENU_HEIGHT);

    private boolean STANDALONE; // new function (mutable)
    private int w;
    private Mode status = Mode.LIVE_STOPPED;
    private Spectrometer spectrum;
    private TimeDomain pausedDisplay;
    private TimeDomain liveDisplay;
    private TimeDomain fileDisplay;

    private TimeDomain timeDomain;
    private final float[] transformBuffer = new float[TRANSFORM];
    private File file;

    // Controls
    private JPanel wrap;
    private JToggleButton liveBtn;
    private JToggleButton zoneBtn;
    private JToggleButton fileBtn;
    private JLabel feedback;
    /** wrapper used so we can swap feedback <-> JavaxIn/Playa panel */
    private final JPanel feedbackWrap = new JPanel();
    /** dynamic box that will hold the current TimeDomain's controls panel */
    private final Box timeControlsBox = new Box(BoxLayout.X_AXIS);
    private final Box content = new Box(BoxLayout.Y_AXIS);

    /** Optional live input helper for standalone mode; only created when STANDALONE==true */
    private JavaxIn javaxIn;
    /** Single shared audio player and UI (Playa) */
    private final JavaxOut out;
    private final Playa playa;

    @SuppressWarnings("resource")
    public static void main(String[] args) {
        new JudahScope();
    }

    /** No-arg constructor: create a STANDALONE full-screen JudahScope with JavaxIn controls. */
    public JudahScope() {
        Nimbus.start();
        this.STANDALONE = true;

        int screenWidth = Math.min(1100, Toolkit.getDefaultToolkit().getScreenSize().width);
        // Shared player for standalone
        this.out = new JavaxOut(null);
        this.playa = new Playa(out);

        JFrame f = new JFrame("JudahScope");
        f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        f.add(this);
        f.setSize(screenWidth, 748);
        f.setLocation(0, 0);
        init(screenWidth);

        f.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) {
                    Dimension box = f.getContentPane().getSize();
                    resized(box.width, box.height);
                }});
        f.setVisible(true);
        setMode(Mode.LIVE_ROLLING);
    }

    /** Normal constructor used by embedding code (non-standalone). */
    public JudahScope(int w) {
        this.STANDALONE = false;
        // Shared player for embedded, too
        this.out = new JavaxOut(null);
        this.playa = new Playa(out);
        init(w);
    }

    /** Shared initialization for both constructors. */
    private void init(int width) {
        this.w = width;
        setName("JudahScope");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { Services.shutdown();}));
        Services.add(this);

        // Create JavaxIn only for standalone mode (embedded containers will not instantiate it)
        javaxIn = STANDALONE ? new JavaxIn(this) : null;
        liveBtn = new JToggleButton("Live", true);
        zoneBtn = new JToggleButton("Stop", false);
        fileBtn = new JToggleButton("File", false);
        ButtonGroup gp = new ButtonGroup();
        gp.add(liveBtn); gp.add(zoneBtn); gp.add(fileBtn);
        liveBtn.addActionListener(l -> setMode(Mode.LIVE_ROLLING));
        zoneBtn.addActionListener(l -> setMode(Mode.LIVE_STOPPED));
        fileBtn.addActionListener(l -> setMode(Mode.FILE));

        // Initialize displays with initial width
        pausedDisplay = new TimeDomain(this, w);
        liveDisplay = new TimeDomain(pausedDisplay, w);
        timeDomain = pausedDisplay;
        spectrum = new Spectrometer(new Dimension(w, 300), liveBtn);
        updateTimeControls(timeDomain);
        feedback = new JLabel(" (load) ", JLabel.CENTER);
        // feedback mouse click triggers file load
        feedback.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { load(); }});

        // Build menu
        Box menu = new Box(BoxLayout.X_AXIS);
        menu.add(Box.createHorizontalStrut(1));
        menu.add(Gui.box(liveBtn, zoneBtn, fileBtn));
        feedbackWrap.setLayout(new BoxLayout(feedbackWrap, BoxLayout.X_AXIS));
        updateFeedbackWrap(); // initial population depending on STANDALONE & mode
        menu.add(feedbackWrap);
        menu.add(spectrum.getControls());
        menu.add(timeControlsBox);
        menu.add(playa);
        menu.add(Box.createHorizontalStrut(1));
        playa.setVisible(false);

        // Build layout
        wrap = Gui.wrap(timeDomain);
        content.add(Gui.resize(menu, new Dimension(w, MENU_HEIGHT)));
        content.add(spectrum);
        content.add(Box.createVerticalStrut(6));
        content.add(wrap);
        content.add(Box.createVerticalGlue());

        setLayout(new GridLayout(1, 1));
        add(Gui.wrap(content));
    }

    /** Swap the feedbackWrap contents:
     *  - STANDALONE & not FILE: JavaxIn devices
     *  - FILE mode: Playa controls
     *  - Else: simple feedback label
     */
    private void updateFeedbackWrap() {
        feedbackWrap.removeAll();
        Component fb;
        if (STANDALONE && status != Mode.FILE && javaxIn != null) {
            fb = javaxIn.getDevices();
        }
        else {
            fb = feedback;
        }
        feedbackWrap.add(Gui.resize(fb, FEEDBACK));

        feedbackWrap.revalidate();
        feedbackWrap.repaint();
    }

    /** Swap the controls panel to match the active TimeDomain.  */
    private void updateTimeControls(TimeDomain td) {
        timeControlsBox.removeAll();
        if (td != null) {
            timeControlsBox.add(td.getControls());
        }
        timeControlsBox.revalidate();
        timeControlsBox.repaint();
    }

    @Override
    public void resized(int width, int height) {
        w = width;

        // Calculate available height for Spectrometer
        int spectrometerHeight = height - MENU_HEIGHT - TimeDomain.TOTAL_HEIGHT - VERTICAL_SPACING;
        spectrometerHeight = Math.max(100, spectrometerHeight); // Minimum height

        // Resize components
        spectrum.resized(w, spectrometerHeight);
        timeDomain.resize(w);

        // Update this panel's size
        Dimension newSize = new Dimension(width, height);
        setPreferredSize(newSize);
        setSize(newSize);

        revalidate();
        repaint();
    }

    /** load a file off the current thread */
    public void load() {
        file = Folders.choose(Folders.getLoops());
        if (file == null)
            return;
        if (!FromDisk.canLoadForScope(file)) {
            RTLogger.warn(this, "Scope load refused for " + file.getName());
            Gui.infoBox("Scope load aborted: file too large for memory.\n" + file.getName(), getName());
            return;
        }
        Threads.execute(() -> {
            Recording recording = MP3.load(file);
            int frames = recording.size() / CHUNKS;
            Transform[] loaded = new Transform[frames];
            final float[] transformBuffer = new float[TRANSFORM];
            long start = System.currentTimeMillis();
            for (int frame = 0; frame < frames; frame++) {
                int idx = frame * FFT_SIZE;
                float[][] snippet = recording.getSamples(idx, FFT_SIZE);
                System.arraycopy(snippet[0], 0, transformBuffer, 0, FFT_SIZE);
                fft.forwardTransform(transformBuffer);
                float[] amplitudes = new float[AMPLITUDES];
                fft.modulus(transformBuffer, amplitudes);
                loaded[frame] = new Transform(amplitudes, AudioMetrics.analyze(snippet));
            }
            long end = System.currentTimeMillis();
            System.out.println(file.getName() + " frames: " + frames + " FFT compute millis: " + (end - start));
            fileDisplay = new TimeDomain(this, w, loaded, recording);
            install(fileDisplay);

            // Attach shared player to this recording and TimeDomain
            playa.attach(recording, fileDisplay, out);
            updateFeedbackWrap();
        });
    }

    private void install(TimeDomain time) {
        timeDomain = time;
        timeDomain.resize(w); // Ensure it has the current width
        timeDomain.generate();
        wrap.removeAll();
        wrap.add(timeDomain);
        updateTimeControls(timeDomain);
        revalidate();
        repaint();
    }

    void setFeedback() {
        switch (status) {
            case LIVE_ROLLING, LIVE_STOPPED -> feedback.setText(" ");
            case FILE -> feedback.setText(file == null ? " (load) " : file.getName());
        }
        updateFeedbackWrap();
    }

    @Override
    public void analyze(float[] left, float[] right) {
        RMS rms = AudioMetrics.analyze(left);
        System.arraycopy(left, 0, transformBuffer, 0, FFT_SIZE);
        fft.forwardTransform(transformBuffer);
        float[] amplitudes = new float[AMPLITUDES];
        fft.modulus(transformBuffer, amplitudes);

        Transform data = new Transform(amplitudes, rms);
        liveDisplay.analyze(data);
        spectrum.analyze(data);
    }

    public void click(Transform t) {
        if (t == null)
            spectrum.clear();
        else
            spectrum.analyze(t);
    }

    /////////////   MODE   //////////////
    public boolean isActive() {
        return status == Mode.LIVE_ROLLING;
    }

    public void setActive(boolean active) {
        setMode(active ? Mode.LIVE_ROLLING : Mode.LIVE_STOPPED);
    }

    public void setMode(Mode stat) {
        if (stat == status)
            return;

        Mode old = status;
        status = stat;

        // Leaving FILE mode: stop shared player
        if (status != Mode.FILE) {
            try { out.play(false); } catch (Throwable ignored) {}
            playa.setVisible(false);
        }

        if (status == Mode.LIVE_ROLLING) {
            // Switching into LIVE_ROLLING: ensure liveDisplay is shown and JavaxIn is (re)connected
            liveDisplay.fullRange();
            install(liveDisplay);
            if (!liveBtn.isSelected())
                liveBtn.setSelected(true);

            // Reconnect/start JavaxIn when in standalone mode so the selected input is active
            if (STANDALONE && javaxIn != null) {
                try {
                    // Start will reopen/connect the currently selected device; it's safe to call repeatedly
                    javaxIn.start();
                } catch (Throwable t) {
                    RTLogger.warn(this, t);
                }
            }
        } else {
            // leaving live rolling mode: stop JavaxIn capture to free device
            if (old == Mode.LIVE_ROLLING && javaxIn != null) {
                try {
                    javaxIn.stop();
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }

            if (status == Mode.LIVE_STOPPED)
                install(pausedDisplay);
            else if (fileDisplay == null)
                load();
            else
                install(fileDisplay);
        }
        setFeedback();
        if (status == Mode.FILE)
            playa.setVisible(true);
        repaint();
    }

    @Override
    public void close()  {
        if (status == Mode.LIVE_ROLLING)
            setMode(Mode.LIVE_STOPPED);
        try { playa.close(); } catch (Throwable ignored) {}
    }

    /** Called by TimeDomain on seek clicks if needed. */
    public void seekToIndex(int idx) {
        playa.seekByIndex(idx);
    }
}