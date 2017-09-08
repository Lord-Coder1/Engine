package renderers.postProcessing;

import resources.shaders.postProcessing.InvertShader;
import toolbox.annotations.*;

/**
 * Inverts the frame's colors.
 */
public class InvertRenderer extends PostProcessingBase {

    /**
     * The only InvertRenderer instance.
     */
    protected static InvertRenderer instance;

    /**
     * Initializes a new InvertRenderer.
     */
    private InvertRenderer() {
        refreshShader();
    }

    @Override
    protected void refreshShader() {
        if (shader == null || !shader.isUsable()) {
            shader = InvertShader.getInstance();
        }
    }

    /**
     * Returns the InvertRenderer instance.
     *
     * @return the InvertRenderer instance
     */
    @NotNull
    public static InvertRenderer getInstance() {
        if (instance == null) {
            instance = new InvertRenderer();
        }
        return instance;
    }

    @Override
    public boolean isUsable() {
        return true;
    }

}
