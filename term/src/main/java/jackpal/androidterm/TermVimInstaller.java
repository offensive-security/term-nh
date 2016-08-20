package jackpal.androidterm;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.view.WindowManager;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import jackpal.androidterm.compat.AndroidCompat;

@SuppressLint("NewApi")
final class TermVimInstaller {
    final static String bootstrap_installer_body = "Now downloading...";
    final static String bootstrap_error_title = "Unable to download";
    final static String bootstrap_error_body = "Unable to download the packages.\n\nCheck your network connection and try again.";
    final static String bootstrap_error_abort = "Abort";
    final static String bootstrap_error_try_again = "Try again";

    static void update(final Activity activity, final Runnable whenDone) {
        String cpu = System.getProperty("os.arch").toLowerCase();
        if (cpu.contains("arm")) {
            cpu = "armeabi";
            cpu += (AndroidCompat.SDK < 16) ? ".old" : "";
        } else if (cpu.contains("x86") || cpu.contains("i686")) {
            cpu = "x86";
        } else {
            Toast.makeText(activity,
                    cpu + " is not supported.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        final String arch = cpu;

        String path = activity.getFilesDir().toString();
        File extfilesdir = (AndroidCompat.SDK >= 8) ? activity.getExternalFilesDir(null) : null;
        final String updatePath = extfilesdir != null ? extfilesdir.toString() : path;

        final ProgressDialog progress = new ProgressDialog(activity);
        progress.setTitle("Update Vim");
        progress.setMessage(bootstrap_installer_body);
        progress.setCancelable(true);
        progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progress.setMax(100);
        progress.show();
        final String version = "version";
        new Thread() {
            @Override
            public void run() {
                try {
                    String url = "https://raw.githubusercontent.com/fuenor/Android-Terminal-Emulator/downloads/apk/vim/%FILE%";
                    // String url = R.string.update_url_text;
                    String downUrl = url.replaceAll("%FILE%", version);
                    final String newdate = version + ".new";
                    saveUrl(updatePath, newdate, downUrl);
                    boolean updated = false;
                    File installed = new File(updatePath + "/" + version);
                    if (installed.exists()) {
                        File newfile = new File(updatePath + "/" + version + ".new");
                        byte[] b1 = new byte[(int) installed.length()];
                        byte[] b2 = new byte[(int) newfile.length()];
                        new FileInputStream(installed).read(b1);
                        new FileInputStream(newfile).read(b2);
                        if (Arrays.equals(b1, b2)) {
                            deleteFileOrFolder(newfile);
                            updated = true;
                            progress.setProgress(99);
                        }
                   }
                   if (!updated) {
                        progress.setProgress(1);
                        url = "https://github.com/fuenor/Android-Terminal-Emulator/blob/downloads/apk/vim/%FILE%?raw=true";
                        // String url = R.string.update_url_bin;
                        String file = "runtime.zip";
                        downUrl = url.replaceAll("%FILE%", file);

                        File runtime = new File(updatePath + "/" + file);
                        if (!runtime.exists()) saveUrl(updatePath, file, downUrl);
                        progress.setProgress(35);
                        installZip(updatePath, new FileInputStream(updatePath + "/" + file));
                        progress.setProgress(70);
                        deleteFileOrFolder(runtime);

                        String bin = "bin-%ARCH%/";
                        bin = bin.replaceAll("%ARCH%", arch);

                        file = "vim";
                        downUrl = url.replaceAll("%FILE%", bin+file);
                        saveUrl(updatePath, file, downUrl);
                        progress.incrementProgressBy(14);
                        file = "xxd";
                        downUrl = url.replaceAll("%FILE%", bin+file);
                        saveUrl(updatePath, file, downUrl);
                        progress.incrementProgressBy(14);

                        File vfile1 = new File(updatePath + "/" + version);
                        deleteFileOrFolder(vfile1);
                        File vfile2 = new File(updatePath + "/" + version+".new");
                        try {
                            if (vfile2.renameTo(vfile1)) {
                                // Log.e(EmulatorDebug.LOG_TAG, "error mv", e);
                            }
                        } catch (SecurityException e) {
                            // Log.e(EmulatorDebug.LOG_TAG, "Bootstrap error", e);
                        }
                        progress.incrementProgressBy(2);
                   }

                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (progress.getProgress() == 99) {
                                Toast.makeText(activity,
                                        R.string.menu_update_vim_latest,
                                        Toast.LENGTH_LONG).show();
                                progress.setProgress(100);
                            } else if ((progress.getProgress() == 100) && (whenDone != null)) {
                                progress.dismiss();
                                whenDone.run();
                            }
                        }
                    });
                } catch (final Exception e) {
                    // Log.e(EmulatorDebug.LOG_TAG, "Bootstrap error", e);
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                new AlertDialog.Builder(activity).setTitle(bootstrap_error_title).setMessage(bootstrap_error_body)
                                    .setNegativeButton(bootstrap_error_abort, new OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface dialog, int which) {
                                            File file = new File(updatePath+"/"+version+".new");
                                            deleteFileOrFolder(file);
                                            dialog.dismiss();
                                        }
                                    }).setPositiveButton(bootstrap_error_try_again, new OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int which) {
                                        dialog.dismiss();
                                        TermVimInstaller.update(activity, whenDone);
                                    }
                                }).show();
                            } catch (WindowManager.BadTokenException e) {
                                // Activity already dismissed - ignore.
                            }
                        }
                    });
                } finally {
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                progress.dismiss();
                            } catch (RuntimeException e) {
                                // Activity already dismissed - ignore.
                            }
                        }
                    });
                }
            }
        }.start();
    }

    public static void saveUrl(final String path, final String file, final String url)
            throws MalformedURLException, IOException {

        File outDir = new File(path);
        if (!(outDir.isDirectory() || outDir.mkdirs())) return;
        String filename = path+"/"+file;

        BufferedInputStream in = null;
        FileOutputStream fout = null;
        try {
            in = new BufferedInputStream(new URL(url).openStream());
            fout = new FileOutputStream(filename);

            final byte data[] = new byte[1024];
            int count;
            while ((count = in.read(data, 0, 1024)) != -1) {
                fout.write(data, 0, count);
            }
        } finally {
            if (in != null) {
                in.close();
            }
            if (fout != null) {
                fout.close();
            }
        }
    }

    public static void installZip(String path, InputStream is) {
        if (is == null) return;
        File outDir = new File(path);
        outDir.mkdirs();
        ZipInputStream zin = new ZipInputStream(new BufferedInputStream(is));
        ZipEntry ze;
        int size;
        byte[] buffer = new byte[8192];

        try {
            while ((ze = zin.getNextEntry()) != null) {
                if (ze.isDirectory()) {
                    File file = new File(path+"/"+ze.getName());
                    if (!file.isDirectory()) file.mkdirs();
                } else {
                    File file = new File(path+"/"+ze.getName());
                    File parentFile = file.getParentFile();
                    parentFile.mkdirs();

                    FileOutputStream fout = new FileOutputStream(file);
                    BufferedOutputStream bufferOut = new BufferedOutputStream(fout, buffer.length);
                    while ((size = zin.read(buffer, 0, buffer.length)) != -1) {
                        bufferOut.write(buffer, 0, size);
                    }
                    bufferOut.flush();
                    bufferOut.close();
                    if (ze.getName().startsWith("bin/")) {
                        if (AndroidCompat.SDK >= 9) file.setExecutable(true, false);
                    }
                }
            }

            byte[] buf = new byte[2048];
            while (is.available() > 0) {
                is.read(buf);
            }
            zin.close();
        } catch (Exception e) {
        }
    }

    /** Delete a folder and all its content or throw. */
    static void deleteFileOrFolder(File fileOrDirectory) {
        File[] children = fileOrDirectory.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteFileOrFolder(child);
            }
        }
        if (!fileOrDirectory.delete()) {
            // throw new RuntimeException("Unable to delete " + (fileOrDirectory.isDirectory() ? "directory " : "file ") + fileOrDirectory.getAbsolutePath());
        }
    }

}
