package android.content.res;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Desktop stand-in: assets are plain files in one directory. */
public class AssetManager {
    private final File root;

    public AssetManager(File root) { this.root = root; }

    public InputStream open(String path) throws IOException { return new FileInputStream(new File(root, path)); }

    public File file(String path) { return new File(root, path); }
}
