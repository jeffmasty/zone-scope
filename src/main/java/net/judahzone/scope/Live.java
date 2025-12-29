package net.judahzone.scope;

import judahzone.util.Recording;

public interface Live {

	public static record LiveData(Live processor, Recording stereo) {}

	void analyze(Recording rec);

}
