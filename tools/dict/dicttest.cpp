// Desktop check of a main dictionary with the keyboard's own native engine.
// Build: xcrun clang++ -O2 -w -isysroot "$(xcrun --show-sdk-path)" -I app/src/main/cpp \
//            tools/dict/dicttest.cpp app/src/main/cpp/dictionary.cpp \
//            app/src/main/cpp/char_utils.cpp -o /tmp/dicttest
// Run:   /tmp/dicttest app/src/main/res/raw-uk/main.dict привіт првіт мясо
// For every typed word prints whether it is valid and the suggestions, the way
// BinaryDictionary.getWords() asks for them (no proximity data: one code per letter).
// Plain C headers only: the Command Line Tools have no C++ standard library headers.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "dictionary.h"

using namespace latinime;

static const int MAX_WORD_LENGTH = 48, MAX_WORDS = 18, MAX_ALTERNATIVES = 16;

/** Decodes UTF-8 into out (zero terminated), returns the length. */
static int fromUtf8(const char *s, unsigned short *out, int max) {
    int n = 0;
    for (const unsigned char *p = (const unsigned char *) s; *p && n < max - 1;) {
        unsigned c = *p++;
        if (c >= 0xE0) { c = ((c & 0x0F) << 12) | ((p[0] & 0x3F) << 6) | (p[1] & 0x3F); p += 2; }
        else if (c >= 0xC0) { c = ((c & 0x1F) << 6) | (p[0] & 0x3F); p += 1; }
        out[n++] = (unsigned short) c;
    }
    out[n] = 0;
    return n;
}

static void printUtf8(const unsigned short *w) {
    for (; *w; w++) {
        unsigned c = *w;
        if (c < 0x80) putchar(c);
        else if (c < 0x800) { putchar(0xC0 | (c >> 6)); putchar(0x80 | (c & 0x3F)); }
        else { putchar(0xE0 | (c >> 12)); putchar(0x80 | ((c >> 6) & 0x3F)); putchar(0x80 | (c & 0x3F)); }
    }
}

int main(int argc, char **argv) {
    if (argc < 3) { fprintf(stderr, "usage: %s main.dict word...\n", argv[0]); return 2; }
    FILE *f = fopen(argv[1], "rb");
    if (!f) { perror(argv[1]); return 1; }
    fseek(f, 0, SEEK_END);
    long size = ftell(f);
    fseek(f, 0, SEEK_SET);
    unsigned char *data = (unsigned char *) malloc(size);
    if (fread(data, 1, size, f) != (size_t) size) return 1;
    fclose(f);
    Dictionary dict(data, 2, 2, (int) size);  // TYPED_LETTER_ and FULL_WORD_FREQ_MULTIPLIER

    for (int a = 2; a < argc; a++) {
        unsigned short typed[MAX_WORD_LENGTH];
        int n = fromUtf8(argv[a], typed, MAX_WORD_LENGTH);
        int codes[MAX_WORD_LENGTH * MAX_ALTERNATIVES];
        for (int i = 0; i < MAX_WORD_LENGTH * MAX_ALTERNATIVES; i++) codes[i] = -1;
        for (int i = 0; i < n; i++) {
            codes[i * MAX_ALTERNATIVES] = typed[i];
            codes[i * MAX_ALTERNATIVES + 1] = 0;
        }
        static unsigned short words[MAX_WORD_LENGTH * MAX_WORDS];
        static int freqs[MAX_WORDS];
        memset(words, 0, sizeof(words));
        memset(freqs, 0, sizeof(freqs));
        bool valid = dict.isValidWord(typed, n);
        int count = dict.getSuggestions(codes, n, words, freqs,
                MAX_WORD_LENGTH, MAX_WORDS, MAX_ALTERNATIVES, -1, NULL, 0);
        if (count < 5) {  // BinaryDictionary: wildcard one position when results are thin
            for (int skip = 0; skip < n; skip++) {
                int c = dict.getSuggestions(codes, n, words, freqs,
                        MAX_WORD_LENGTH, MAX_WORDS, MAX_ALTERNATIVES, skip, NULL, 0);
                if (c > count) count = c;
                if (c > 0) break;
            }
        }
        printf("%s%s:", argv[a], valid ? " (valid)" : "");
        for (int j = 0; j < count && j < 8; j++) {
            if (freqs[j] < 1) break;
            putchar(' ');
            printUtf8(&words[j * MAX_WORD_LENGTH]);
            printf("/%d", freqs[j]);
        }
        printf("\n");
    }
    return 0;
}
