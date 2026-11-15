/*
 * Copyright (C) 2026 Yaroslav Bezzubets
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.pocketworkstation.pckeyboard.gesture.GestureDecoder;
import org.pocketworkstation.pckeyboard.gesture.GestureKeyMap;
import org.pocketworkstation.pckeyboard.gesture.GestureParams;
import org.pocketworkstation.pckeyboard.gesture.GesturePath;
import org.pocketworkstation.pckeyboard.gesture.GestureResult;
import org.pocketworkstation.pckeyboard.gesture.Lexicon;
import org.pocketworkstation.pckeyboard.gesture.LexiconBinding;

/**
 * Accuracy and speed of the gesture decoder on synthetic gestures over the app's real
 * layouts. See run.sh for the options.
 */
public final class GestureHarness {
    public static void main(String[] args) throws Exception {
        String res = null, assets = null, lang = "uk", layout = "qwerty";
        int top = 1000, skip = 0, width = 1080, keyH = 160, examples = 20;
        float noise = 0.25f, cut = 0f;
        long seed = 42;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--res": res = args[++i]; break;
                case "--assets": assets = args[++i]; break;
                case "--lang": lang = args[++i]; break;
                case "--layout": layout = args[++i]; break;
                case "--top": top = Integer.parseInt(args[++i]); break;
                case "--skip": skip = Integer.parseInt(args[++i]); break;
                case "--width": width = Integer.parseInt(args[++i]); break;
                case "--keyh": keyH = Integer.parseInt(args[++i]); break;
                case "--noise": noise = Float.parseFloat(args[++i]); break;
                case "--cut": cut = Float.parseFloat(args[++i]); break;
                case "--seed": seed = Long.parseLong(args[++i]); break;
                case "--examples": examples = Integer.parseInt(args[++i]); break;
                case "--set":
                    for (String kv : args[++i].split(",")) {
                        String[] p = kv.split("=");
                        if (!GestureParams.set(p[0].trim(), p[1].trim())) {
                            throw new IllegalArgumentException("unknown parameter " + kv);
                        }
                    }
                    break;
                default:
                    throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }

        LayoutXml xml = LayoutXml.load(new File(res), lang, layout, width, keyH);
        GestureKeyMap keys = xml.keyMap();

        Runtime rt = Runtime.getRuntime();
        System.gc();
        long memBefore = rt.totalMemory() - rt.freeMemory();
        long t0 = System.nanoTime();
        Lexicon lex = Lexicon.load(new FileInputStream(new File(assets, "gesture/" + lang + ".txt")));
        long t1 = System.nanoTime();
        LexiconBinding binding = new LexiconBinding(lex, keys);
        long t2 = System.nanoTime();
        System.gc();
        long memAfter = rt.totalMemory() - rt.freeMemory();

        // Test words: the most frequent ones a gesture can type (two keys or more)
        PathSynth synth = new PathSynth(keys, noise, cut, seed);
        List<String> words = new ArrayList<>();
        List<int[]> seqs = new ArrayList<>();
        int excluded = 0;
        for (int w = 0, taken = 0; w < lex.size() && words.size() < top; w++) {
            String word = lex.word(w);
            if (word.length() < 2) continue;
            if (taken++ < skip) continue;
            int[] seq = synth.keysOf(word);
            if (seq == null || seq.length < 2) {
                excluded++;
                continue;
            }
            words.add(word);
            seqs.add(seq);
        }
        List<GesturePath> paths = new ArrayList<>();
        for (int[] seq : seqs) paths.add(synth.trace(seq));

        GestureDecoder decoder = new GestureDecoder();
        // Warm up the JIT
        for (int i = 0; i < 200; i++) decoder.decode(paths.get(i % paths.size()), binding, 5);

        int n = words.size();
        int top1 = 0, top3 = 0, top5 = 0, ambiguous1 = 0, empty = 0;
        double mrr = 0;
        long[] nanos = new long[n];
        long scanned = 0;
        int[][] byLen = new int[4][3]; // count, top1, top3
        List<String> confusions = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String expected = words.get(i);
            GestureResult r = decoder.decode(paths.get(i), binding, GestureParams.MAX_OUT);
            nanos[i] = r.nanos;
            scanned += r.candidatesScanned;
            int rank = -1;
            for (int j = 0; j < r.words.length; j++) {
                if (r.words[j].equals(expected)) {
                    rank = j;
                    break;
                }
            }
            if (r.isEmpty()) empty++;
            if (rank == 0) top1++;
            if (rank >= 0 && rank < 3) top3++;
            if (rank >= 0 && rank < 5) top5++;
            if (rank >= 0) mrr += 1.0 / (rank + 1);
            // Same keys as the expected word and at least as frequent: no gesture can tell them apart
            if (rank == 0 || (!r.isEmpty()
                    && Arrays.equals(synth.keysOf(r.words[0]), seqs.get(i))
                    && lex.indexOf(r.words[0]) >= 0
                    && lex.indexOf(r.words[0]) <= lex.indexOf(expected))) {
                ambiguous1++;
            }
            int len = expected.length();
            int bucket = len <= 3 ? 0 : len <= 5 ? 1 : len <= 8 ? 2 : 3;
            byLen[bucket][0]++;
            if (rank == 0) byLen[bucket][1]++;
            if (rank >= 0 && rank < 3) byLen[bucket][2]++;
            if (rank != 0 && confusions.size() < examples) {
                confusions.add(expected + " -> " + (r.isEmpty() ? "(nothing)"
                        : String.join(", ", Arrays.asList(r.words).subList(0, Math.min(3, r.words.length)))));
            }
        }
        long[] sorted = nanos.clone();
        Arrays.sort(sorted);
        double mean = Arrays.stream(nanos).average().orElse(0) / 1e6;
        double p95 = sorted[Math.min(n - 1, (int) (n * 0.95))] / 1e6;

        System.out.printf(Locale.ROOT, "lang=%s layout=%s width=%d keyH=%d noise=%.2f cut=%.2f seed=%d words=%d (skip %d, %d not typable)%n",
                lang, layout, width, keyH, noise, cut, seed, n, skip, excluded);
        System.out.printf(Locale.ROOT, "letter keys=%d keyW=%.0f keyH=%.0f lexicon=%d bound=%d load=%.0fms bind=%.0fms mem=%.1fMB%n",
                keys.getKeyCount(), keys.getKeyWidth(), keys.getKeyHeight(), lex.size(), binding.size(),
                (t1 - t0) / 1e6, (t2 - t1) / 1e6, (memAfter - memBefore) / 1048576.0);
        System.out.printf(Locale.ROOT, "top1=%.3f top3=%.3f top5=%.3f mrr=%.3f top1(ambiguity-aware)=%.3f empty=%d%n",
                top1 / (double) n, top3 / (double) n, top5 / (double) n, mrr / n, ambiguous1 / (double) n, empty);
        String[] names = { "2-3", "4-5", "6-8", "9+" };
        for (int b = 0; b < 4; b++) {
            if (byLen[b][0] == 0) continue;
            System.out.printf(Locale.ROOT, "  len %-4s n=%4d top1=%.3f top3=%.3f%n", names[b], byLen[b][0],
                    byLen[b][1] / (double) byLen[b][0], byLen[b][2] / (double) byLen[b][0]);
        }
        System.out.printf(Locale.ROOT, "decode mean=%.2fms p95=%.2fms candidates=%.0f%n", mean, p95,
                scanned / (double) n);
        for (String c : confusions) System.out.println("  " + c);
    }
}
