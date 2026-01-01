package net.judahzone.scope;

import java.awt.FlowLayout;
import java.io.Closeable;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;

import judahzone.api.PlayAudio;
import judahzone.gui.Gui;
import judahzone.javax.JavaxOut;
import judahzone.util.Recording;
import judahzone.util.WavConstants;

/**
 * Playa — GUI wrapper. Delegates playback to a shared PlayAudio implementation (JavaxOut).
 *
 * This class does NOT own the player; JudahScope provides the shared JavaxOut.
 */
public class Playa extends JPanel implements Closeable {

    private final JavaxOut back; // shared player provided by JudahScope

    // UI components
    private final JToggleButton playButton = new JToggleButton("▶️");
    private final JSlider gainSlider = new JSlider(0, 100, 50); // 0..100 -> mastering 0..2
    private final JToggleButton loopButton = new JToggleButton("🔁");

    public Playa(JavaxOut back) {
        super(new FlowLayout(FlowLayout.LEFT, 4, 2));
        this.back = back;

        gainSlider.setToolTipText("Output gain (mastering)");
        gainSlider.setValue(50);
        back.setMastering(gainSlider.getValue() / 50f);
        gainSlider.addChangeListener(e -> {
            float m = gainSlider.getValue() / 50f;
            back.setMastering(m);
        });

        playButton.addActionListener(a -> {
            if (back.isPlaying()) {
                back.play(false);
                SwingUtilities.invokeLater(() -> {
                    playButton.setSelected(false);
                    playButton.setText("▶️");
                });
            } else {
                back.setType(loopButton.isSelected() ? PlayAudio.Type.LOOP : PlayAudio.Type.ONE_SHOT);
                back.play(true);
                SwingUtilities.invokeLater(() -> {
                    playButton.setSelected(true);
                    playButton.setText("❚❚");
                });
            }
        });

        loopButton.addActionListener(a -> {
            back.setType(loopButton.isSelected() ? PlayAudio.Type.LOOP : PlayAudio.Type.ONE_SHOT);
        });

        add(new JLabel(" Vol"));
        add(Gui.resize(gainSlider, JudahScope.SLIDER));
        add(playButton);
        add(loopButton);
    }

    /**
     * Attach a recording and TimeDomain to the shared player.
     */
    public synchronized void attach(Recording tape, TimeDomain td, PlayAudio player) {
        if (player instanceof JavaxOut jx) {
            jx.setTimeDomain(td);
        }
        player.setRecording(tape);
        SwingUtilities.invokeLater(() -> {
            playButton.setSelected(player.isPlaying());
            playButton.setText(player.isPlaying() ? "❚❚" : "▶️");
        });
    }

    /** Seek by FFT-window index. */
    public void seekByIndex(int idx) {
        long frame = (long) idx * (long) WavConstants.FFT_SIZE;
        if (frame < 0) frame = 0;
        long total = back.getLength();
        if (total > 0 && frame >= total) frame = Math.max(0, total - 1);
        back.setPlaySampleFrame(frame);
    }

    @Override
    public void close() {
        back.play(false);
        if (back instanceof AutoCloseable ac) {
            try { ac.close(); } catch (Exception ignored) {}
        }
    }
}