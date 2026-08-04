package com.fand1l.vibeweather.client;

/**
 * Lets the client tick tell the cloud renderer how much of the sky to fill.
 *
 * <p>Implemented by the mixin on {@code CloudRenderer}, so the call site does not have to know that
 * a mixin is involved, and the mixin does not have to reach out into the tick. The alternative --
 * doing the work inside the colour hook that already runs each frame -- would have one method
 * quietly rebuilding a texture as a side effect of being asked for a colour.
 */
public interface CloudDensityTarget {
	/**
	 * @param density fraction of cloud cells that should exist, 0 to 1
	 */
	void vibeweather$setCloudDensity(float density);
}
