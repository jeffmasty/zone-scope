package judahzone.scope;

import static judahzone.util.WavConstants.FFT_SIZE;

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
import javax.swing.SwingUtilities;

import judahzone.api.Asset;
import judahzone.api.FX.Registrar;
import judahzone.api.PlayAudio;
import judahzone.api.Transform;
import judahzone.fx.analysis.Transformer;
import judahzone.gui.Floating;
import judahzone.gui.Gui;
import judahzone.gui.Nimbus;
import judahzone.javax.JavaxIn;
import judahzone.javax.JavaxOut;
import judahzone.util.Constants;
import judahzone.util.Folders;
import judahzone.util.MP3;
import judahzone.util.Memory;
import judahzone.util.RTLogger;
import judahzone.util.Recording;
import judahzone.util.Services;
import judahzone.util.Threads;
import judahzone.util.WavConstants;
import judahzone.widgets.BoomBox;

/** Provides a Spectrometer, a Spectrogram and RMSmeter, listening to mixer's selected channels
 * (on a circular 20 sec buffer) or analyze an audio file from disk */
public class JudahScope extends JPanel implements Floating, Closeable {

	public static enum Mode{ LIVE_ROLLING, LIVE_STOPPED, FILE }

	private static final int MENU_HEIGHT = 32;
	private static final int VERTICAL_SPACING = 26;
	public static final Dimension SLIDER = new Dimension(60, 24);
	public static final Dimension FEEDBACK = new Dimension(190, MENU_HEIGHT);

	private final boolean STANDALONE;
	private int w;
	private Mode mode = Mode.LIVE_STOPPED;
	private Spectrometer spectrum;
	private TimeDomain timeDomain;

	// Data sources
	private Transform[] liveDb;
	private Transform[] fileDb;
	private Recording fileRecording;
	private File file;

	/** live JavaSound input for STANDALONE mode*/
	private JavaxIn javaxIn;
	private Registrar zone; // only for embedded mode
	/** shared audio player (low level) (Jack or JavaSound) */
	private final PlayAudio out;
	/** shared generic audio player GUI wrapper */
	private BoomBox boombox;

	private final Transformer analyzer = new Transformer(transform -> {
    	SwingUtilities.invokeLater(() -> {
    		if (mode == Mode.LIVE_ROLLING)
    			timeDomain.analyze(transform);

    		spectrum.analyze(transform);
    	});
    });

	// Controls
	private JToggleButton liveBtn;
	private JToggleButton stopBtn;
	private JToggleButton fileBtn;
	private JLabel feedback;
	/** wrapper used so we can swap feedback <-> JavaxIn/Playa panel */
	private final JPanel feedbackWrap = new JPanel();


	public static void main(String[] args) {
	    SwingUtilities.invokeLater(() -> {
	    	@SuppressWarnings("resource")
			JudahScope scope = new JudahScope();
	    	// If a file argument was provided, start loading it (async)
	    	if (args != null && args.length > 0) {
	    		File argFile = new File(args[0]);
	    		if (argFile.exists())
	    			scope.loadFile(argFile);
	    		else
	    			System.err.println("Startup file not found: " + args[0]);
	    	}
	    });
	}


	/** No-arg constructor: create a STANDALONE fullscreen-ish JudahScope with JavaxIn controls. */
	public JudahScope() {
	    Nimbus.start();
	    this.STANDALONE = true;

	    // JavaSound player for standalone
	    this.out = new JavaxOut();

	    int screenWidth = Math.min(1100, Toolkit.getDefaultToolkit().getScreenSize().width);
	    JFrame f = new JFrame(JudahScope.class.getSimpleName());
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
	}

	/** Normal constructor used by embedding code (non-standalone).
	 *  Accepts an externally provided PlayAudio (e.g. JACK) and a Registrar.
	 */
	public JudahScope(int w, PlayAudio out, Registrar zone) {
	    this.STANDALONE = false;
	    // Embedded: use provided player and GUI wrapper
	    this.out = out;
	    this.zone = zone;
	    init(w);
	}

	/** Shared initialization for both constructors. */
	private void init(int width) {
	    this.w = width;
	    setName("JudahScope");
	    Runtime.getRuntime().addShutdownHook(new Thread(() -> { Services.shutdown();}));
	    Services.add(this);

	    // Create JavaxIn only for standalone mode
	    javaxIn = STANDALONE ? new JavaxIn(analyzer) : null;
	    liveBtn = new JToggleButton("Live", true);
	    stopBtn = new JToggleButton("Pause", false);
	    fileBtn = new JToggleButton("File", false);
	    ButtonGroup gp = new ButtonGroup();
	    gp.add(liveBtn); gp.add(stopBtn); gp.add(fileBtn);
	    liveBtn.addActionListener(l -> setMode(Mode.LIVE_ROLLING));
	    stopBtn.addActionListener(l -> setMode(Mode.LIVE_STOPPED));
	    fileBtn.addActionListener(l -> {
	        if (mode == Mode.FILE)
	            load();               // bring up open dialog when already in FILE
	        else
	            setMode(Mode.FILE);   // normal mode switch
	    });
	    // Initialize data and displays
	    liveDb = new Transform[w / 2]; // Initial live buffer
	    timeDomain = new TimeDomain(this, w, liveDb);
	    spectrum = new Spectrometer(new Dimension(w, 300), liveBtn);
	    feedback = new JLabel(" (load) ", JLabel.CENTER);
	    feedback.addMouseListener(new MouseAdapter() {
	        @Override public void mouseClicked(MouseEvent e) { load(); }});
	    boombox = new BoomBox(out, timeDomain, SLIDER);
	    boombox.setVisible(false);

	    // Build menu
	    Box menu = new Box(BoxLayout.X_AXIS);
	    menu.add(Box.createHorizontalStrut(1));
	    menu.add(Gui.box(liveBtn, stopBtn, fileBtn));
	    feedbackWrap.setLayout(new BoxLayout(feedbackWrap, BoxLayout.X_AXIS));
	    updateFeedbackWrap();
	    menu.add(feedbackWrap);
	    menu.add(spectrum.getControls());
	    menu.add(timeDomain.getControls());
	    menu.add(boombox);
	    menu.add(Box.createHorizontalStrut(1));

	    // Build layout
	    Box content = new Box(BoxLayout.Y_AXIS);
	    content.add(Gui.resize(menu, new Dimension(w, MENU_HEIGHT)));
	    content.add(spectrum);
	    content.add(Box.createVerticalStrut(6));
	    content.add(Gui.wrap(timeDomain));
	    content.add(Box.createVerticalGlue());

	    setLayout(new GridLayout(1, 1));
	    add(Gui.wrap(content));
	    setMode(Mode.LIVE_ROLLING);
	}

	private void updateFeedbackWrap() {
	    feedbackWrap.removeAll();
	    Component fb = feedback;
	    if (STANDALONE && mode != Mode.FILE && javaxIn != null)
	        fb = javaxIn.getDevices();
	    feedbackWrap.add(Gui.resize(fb, FEEDBACK));
	    feedbackWrap.revalidate();
	    feedbackWrap.repaint();
	}

	public Mode getMode() {
	    return mode;
	}

	@Override
	public void resized(int width, int height) {
	    w = width;
	    int spectrometerHeight = Math.max(100, height - MENU_HEIGHT - TimeDomain.TOTAL_HEIGHT - VERTICAL_SPACING);
	    spectrum.resized(w, spectrometerHeight);
	    timeDomain.resize(w);
	    Dimension newSize = new Dimension(width, height);
	    setPreferredSize(newSize);
	    setSize(newSize);
	    revalidate();
	    repaint();
	}

	public void loadFile(File f) {
        if (f == null) return;

        // remember file so feedback and setMode can show the correct name
        this.file = f;
        if (!Memory.checkFFT(f)) // user cancelled due to not enough memory
        	return;

        Threads.execute(() -> {
            fileRecording = MP3.load(f);
            int frames = fileRecording.size() / WavConstants.CHUNKS;
            fileDb = new Transform[frames];
            long start = System.currentTimeMillis();
            for (int frame = 0; frame < frames; frame++) {
                int idx = frame * FFT_SIZE;
                float[][] snippet = fileRecording.getSamples(idx, FFT_SIZE);
                fileDb[frame] = analyzer.analyze(snippet[0], snippet[1]);
            }
            long end = System.currentTimeMillis();
            System.out.println(f.getName() + " frames: " + frames + " FFT compute millis: " + (end - start));
            boombox.setRecording(new Asset(f.getName(), f, fileRecording, fileRecording.size() * Constants.bufSize(),
                    Asset.Category.USER));

            SwingUtilities.invokeLater(() -> {
                // If already viewing a file, refresh the TimeDomain and wiring so the new file
                // and filename show immediately. Otherwise switch into FILE mode (normal path).
                if (mode == Mode.FILE) {
                    // update TimeDomain data and playback wiring in-place
                    timeDomain.setData(fileDb, fileRecording);
                    try { out.setPlayed(boombox); } catch (Throwable t) { RTLogger.warn(this, t); }
                    timeDomain.setPlaya(boombox);
                    setFeedback();
                    repaint();
                } else {
                    setMode(Mode.FILE);
                }
            });
        });
    }


	public void load() {
	    file = Folders.choose(Folders.getLoops());
	    if (file == null) return;
	    // delegate to new loader
	    loadFile(file);
	}


	void setFeedback() {
	    switch (mode) {
	        case LIVE_ROLLING, LIVE_STOPPED -> feedback.setText(" ");
	        case FILE -> feedback.setText(file == null ? " (load) " : file.getName());
	    }
	    updateFeedbackWrap();
	}

	public void click(Transform t) {
	    if (t == null)
	        spectrum.clear();
	    else
	        spectrum.analyze(t);
	}

	public boolean isActive() {
	    return mode == Mode.LIVE_ROLLING;
	}

	public void setActive(boolean active) {
	    setMode(active ? Mode.LIVE_ROLLING : Mode.LIVE_STOPPED);
	}

	public void setMode(Mode newMode) {
	    if (newMode == mode) return;

	    Mode oldMode = mode;

	    // If switching to FILE but no file is loaded yet, start async load and keep current mode.
	    if (newMode == Mode.FILE && fileDb == null) {
	        load();
	        return;
	    }

	    // Capture last live head if we're leaving LIVE_ROLLING -> LIVE_STOPPED
	    int lastLiveHead = -1;
	    if (oldMode == Mode.LIVE_ROLLING && newMode == Mode.LIVE_STOPPED) {
	        try {
	            lastLiveHead = timeDomain.getPositionIndex();
	        } catch (Throwable ignored) {
	            lastLiveHead = -1;
	        }
	    }

	    // Now commit the mode change and perform transitions.
	    mode = newMode;

	    // Stop player when leaving FILE mode
	    if (oldMode == Mode.FILE && newMode != Mode.FILE) {
	        boombox.play(false);
	        try { out.setPlayed(null); } catch (Throwable t) { RTLogger.warn(this, t); }
	        timeDomain.setPlaya(null);
	    }

	    // Stop live input when leaving LIVE_ROLLING mode
	    if (oldMode == Mode.LIVE_ROLLING && newMode != Mode.LIVE_ROLLING) {
	        if (STANDALONE && javaxIn != null) {
	            try { javaxIn.stop(); } catch (Throwable t) { RTLogger.warn(this, t); }
	        }
	        if (!STANDALONE && analyzer != null && zone != null) {
	            zone.unregister(analyzer);
	        }
	    }

	    // --- Configure for new mode ---
	    switch (newMode) {
	        case LIVE_ROLLING:
	            timeDomain.setData(liveDb, null);
	            timeDomain.fullRange();
	            if (!liveBtn.isSelected()) liveBtn.setSelected(true);

	            if (STANDALONE && javaxIn != null) {
	                try { javaxIn.start(); } catch (Throwable t) { RTLogger.warn(this, t); }
	            }
	            if (!STANDALONE && analyzer != null && zone != null) {
	                zone.register(analyzer);
	            }
	            break;

	        case LIVE_STOPPED:
	            timeDomain.setData(liveDb, null);
	            if (!stopBtn.isSelected()) stopBtn.setSelected(true);
	            // restore the last live head position so the stopped view paints that head
	            if (lastLiveHead >= 0) {
	                timeDomain.setPositionIndex(lastLiveHead);
	            }
	            break;

	        case FILE:
	            // fileDb must be present here (we returned earlier if it wasn't)
	            timeDomain.setData(fileDb, fileRecording);
	            // Wire player callbacks to BoomBox (which forwards setHead into TimeDomain)
	            try { out.setPlayed(boombox); } catch (Throwable t) { RTLogger.warn(this, t); }
	            timeDomain.setPlaya(out);
	            if (!fileBtn.isSelected()) fileBtn.setSelected(true);
	            break;
	    }

	    boombox.setVisible(newMode == Mode.FILE);
	    setFeedback();
	    repaint();
	}
	@Override
	public void close()  {
	    if (mode == Mode.LIVE_ROLLING)
	        setMode(Mode.LIVE_STOPPED);
	    try { boombox.close(); } catch (Throwable ignored) {}
	}

	/** Called by TimeDomain on seek clicks if needed. */
	public void seekToIndex(int idx) {
	    try {
	        long sampleFrame = (long) idx * FFT_SIZE;
	        boombox.setSample(sampleFrame);
	    } catch (Throwable t) {
	    	System.err.println("Error seeking to index " + idx);
	    }
	}
}
