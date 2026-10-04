package android.content;

import android.content.res.AssetManager;
import java.io.File;

/** Desktop stand-in: only what the brain classes call (assets, files directory). */
public class Context {
    private final AssetManager assets;
    private final File files;

    public Context(File assets, File files) {
        this.assets = new AssetManager(assets);
        this.files = files;
    }

    public AssetManager getAssets() { return assets; }
    public File getFilesDir() { return files; }
    public File getExternalFilesDir(String type) { return null; }
}
