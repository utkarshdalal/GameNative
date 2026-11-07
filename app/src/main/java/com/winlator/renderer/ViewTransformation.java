package com.winlator.renderer;

public class ViewTransformation {
    // User-requested shift of the letterboxed image within the free space around it,
    // as a fraction of the available slack: -1 = flush left/top, 0 = centered,
    // +1 = flush right/bottom. Shared by every renderer and touch mapper so the
    // picture and the input mapping always move together. viewOffsetX/Y are
    // expressed with a top-left origin; GL consumers must flip Y themselves.
    private static volatile float userOffsetXFraction = 0.0f;
    private static volatile float userOffsetYFraction = 0.0f;
    private static volatile int userOffsetVersion = 0;

    public int viewOffsetX;
    public int viewOffsetY;
    public int viewWidth;
    public int viewHeight;
    public float aspect;
    public float sceneScaleX;
    public float sceneScaleY;
    public float sceneOffsetX;
    public float sceneOffsetY;

    public static void setUserOffset(float xFraction, float yFraction) {
        userOffsetXFraction = Math.max(-1.0f, Math.min(1.0f, xFraction));
        userOffsetYFraction = Math.max(-1.0f, Math.min(1.0f, yFraction));
        userOffsetVersion++;
    }

    public static int getUserOffsetVersion() {
        return userOffsetVersion;
    }

    public void update(int outerWidth, int outerHeight, int innerWidth, int innerHeight) {
        aspect = Math.min((float)outerWidth / innerWidth, (float)outerHeight / innerHeight);
        viewWidth = (int)Math.ceil(innerWidth * aspect);
        viewHeight = (int)Math.ceil(innerHeight * aspect);
        viewOffsetX = (int)((outerWidth - innerWidth * aspect) * 0.5f);
        viewOffsetY = (int)((outerHeight - innerHeight * aspect) * 0.5f);

        int shiftX = Math.round(userOffsetXFraction * (outerWidth - viewWidth) * 0.5f);
        int shiftY = Math.round(userOffsetYFraction * (outerHeight - viewHeight) * 0.5f);
        viewOffsetX += shiftX;
        viewOffsetY += shiftY;

        sceneScaleX = (innerWidth * aspect) / outerWidth;
        sceneScaleY = (innerHeight * aspect) / outerHeight;
        sceneOffsetX = (innerWidth - innerWidth * sceneScaleX) * 0.5f;
        sceneOffsetY = (innerHeight - innerHeight * sceneScaleY) * 0.5f;
        // Same shift in scene units: scene coords map to screen px by outer/inner.
        sceneOffsetX += shiftX * ((float)innerWidth / outerWidth);
        sceneOffsetY += shiftY * ((float)innerHeight / outerHeight);
    }
}
