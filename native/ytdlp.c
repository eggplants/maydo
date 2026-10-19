/*
 * The yt-dlp that mpv's ytdl_hook runs: ytdl_hook takes the path of one executable, but yt-dlp is
 * a zipapp that Python has to be started on. This starts Python on it with yt-dlp's arguments.
 *
 * The app starts it with MAYDO_PYTHON (youtubedl-android's Python launcher in nativeLibraryDir)
 * and MAYDO_YTDLP (the zipapp in the app's files) in its environment, along with what Python
 * needs (PYTHONHOME, LD_LIBRARY_PATH, SSL_CERT_FILE, ...).
 */
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>

int main(int argc, char **argv) {
    const char *python = getenv("MAYDO_PYTHON");
    const char *ytdlp = getenv("MAYDO_YTDLP");
    if (python == NULL || ytdlp == NULL) {
        fputs("maydo: MAYDO_PYTHON and MAYDO_YTDLP must be set\n", stderr);
        return 127;
    }

    char **args = calloc((size_t)argc + 2, sizeof *args);
    if (args == NULL) {
        perror("maydo");
        return 127;
    }
    args[0] = (char *)python;
    args[1] = (char *)ytdlp;
    for (int i = 1; i < argc; i++) {
        args[i + 1] = argv[i];
    }

    execv(python, args);
    perror(python);
    return 127;
}
