package dev.sapperhelper;

import java.util.*;

/** Pure-Java minesweeper probability solver (no Minecraft dependencies). */
public final class Solver {
    public static final int CLOSED = 9;
    private static final long NODE_LIMIT = 4_000_000L;
    private static final double EPS = 1e-9;
    private static final double[][] C = new double[128][128];

    static {
        for (int i = 0; i < C.length; i++) {
            C[i][0] = 1;
            for (int j = 1; j <= i; j++) C[i][j] = C[i - 1][j - 1] + (j <= i - 1 ? C[i - 1][j] : 0);
        }
    }

    private static double binom(int n, int k) {
        if (n < 0 || k < 0 || k > n) return 0;
        return C[n][k];
    }

    private static final class Comp {
        int[] cells;
        int[][] consVars;
        int[] consNeed;
        double[] counts;
        double[][] cellMines;
    }

    private static final class Enumerator {
        final int m;
        final int[][] consVars;
        final int[] need, len;
        final int[][] varCons;
        final int[] assigned, minesIn, val;
        final double[] counts;
        final double[][] cellMines;
        long nodes = 0;
        boolean aborted = false;

        Enumerator(Comp c) {
            m = c.cells.length;
            consVars = c.consVars;
            need = c.consNeed;
            int nc = need.length;
            len = new int[nc];
            List<List<Integer>> vc = new ArrayList<>();
            for (int i = 0; i < m; i++) vc.add(new ArrayList<>());
            for (int ci = 0; ci < nc; ci++) {
                len[ci] = consVars[ci].length;
                for (int v : consVars[ci]) vc.get(v).add(ci);
            }
            varCons = new int[m][];
            for (int i = 0; i < m; i++) {
                varCons[i] = vc.get(i).stream().mapToInt(Integer::intValue).toArray();
            }
            assigned = new int[nc];
            minesIn = new int[nc];
            val = new int[m];
            counts = new double[m + 1];
            cellMines = new double[m + 1][m];
        }

        void rec(int idx, int k) {
            if (aborted) return;
            if (++nodes > NODE_LIMIT) { aborted = true; return; }
            if (idx == m) {
                counts[k]++;
                for (int v = 0; v < m; v++) if (val[v] == 1) cellMines[k][v]++;
                return;
            }
            for (int x = 0; x <= 1; x++) {
                val[idx] = x;
                for (int ci : varCons[idx]) { assigned[ci]++; minesIn[ci] += x; }
                boolean ok = true;
                for (int ci : varCons[idx]) {
                    int rem = len[ci] - assigned[ci];
                    if (minesIn[ci] > need[ci] || minesIn[ci] + rem < need[ci]) { ok = false; break; }
                }
                if (ok) rec(idx + 1, k + x);
                for (int ci : varCons[idx]) { assigned[ci]--; minesIn[ci] -= x; }
                if (aborted) return;
            }
        }
    }

    private static int find(int[] p, int x) {
        while (p[x] != x) { p[x] = p[p[x]]; x = p[x]; }
        return x;
    }

    private static double[] conv(double[] a, double[] b) {
        double[] r = new double[a.length + b.length - 1];
        for (int i = 0; i < a.length; i++) {
            if (a[i] == 0) continue;
            for (int j = 0; j < b.length; j++) r[i + j] += a[i] * b[j];
        }
        return r;
    }

    public static double[] solve(int[] g, int w, int h, int mines, double density) {
        int n = w * h;
        double[] prob = new double[n];
        Arrays.fill(prob, Double.NaN);
        int closedCount = 0;
        for (int i = 0; i < n; i++) if (g[i] == CLOSED) closedCount++;
        if (closedCount == 0) return prob;

        List<int[]> cons = new ArrayList<>();
        boolean[] front = new boolean[n];
        for (int i = 0; i < n; i++) {
            if (g[i] == CLOSED) continue;
            int x = i % w, y = i / w;
            List<Integer> nb = new ArrayList<>();
            for (int dy = -1; dy <= 1; dy++)
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dy == 0) continue;
                    int nx = x + dx, ny = y + dy;
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                    int j = ny * w + nx;
                    if (g[j] == CLOSED) nb.add(j);
                }
            if (nb.isEmpty()) continue;
            int[] c = new int[nb.size() + 1];
            c[0] = g[i];
            for (int k = 0; k < nb.size(); k++) { c[k + 1] = nb.get(k); front[nb.get(k)] = true; }
            cons.add(c);
        }

        int[] par = new int[n];
        for (int i = 0; i < n; i++) par[i] = i;
        for (int[] c : cons) for (int k = 2; k < c.length; k++) par[find(par, c[k])] = find(par, c[1]);

        Map<Integer, List<Integer>> groups = new TreeMap<>();
        for (int i = 0; i < n; i++) if (front[i]) groups.computeIfAbsent(find(par, i), k -> new ArrayList<>()).add(i);

        List<Comp> comps = new ArrayList<>();
        int failedCells = 0;
        double dens = density > 0 && density < 1 ? density : 0.2;
        for (Map.Entry<Integer, List<Integer>> e : groups.entrySet()) {
            List<Integer> cellsL = e.getValue();
            Comp c = new Comp();
            c.cells = cellsL.stream().mapToInt(Integer::intValue).toArray();
            Map<Integer, Integer> local = new HashMap<>();
            for (int i = 0; i < c.cells.length; i++) local.put(c.cells[i], i);
            List<int[]> mine = new ArrayList<>();
            for (int[] cc : cons) if (find(par, cc[1]) == e.getKey()) mine.add(cc);
            c.consVars = new int[mine.size()][];
            c.consNeed = new int[mine.size()];
            for (int i = 0; i < mine.size(); i++) {
                int[] cc = mine.get(i);
                c.consNeed[i] = cc[0];
                c.consVars[i] = new int[cc.length - 1];
                for (int k = 1; k < cc.length; k++) c.consVars[i][k - 1] = local.get(cc[k]);
            }
            Enumerator en = new Enumerator(c);
            en.rec(0, 0);
            double total = 0;
            for (double v : en.counts) total += v;
            if (en.aborted || total == 0) {
                failedCells += c.cells.length;
                for (int cell : c.cells) prob[cell] = dens;
                continue;
            }
            c.counts = en.counts;
            c.cellMines = en.cellMines;
            comps.add(c);
        }

        int frontOk = 0;
        for (Comp c : comps) frontOk += c.cells.length;
        int other = closedCount - frontOk - failedCells;

        boolean ok = mines > 0 && combine(comps, other, mines, dens, true, prob, g);
        if (!ok) combine(comps, other, mines, dens, false, prob, g);

        for (int i = 0; i < n; i++) {
            if (Double.isNaN(prob[i])) continue;
            if (prob[i] < EPS) prob[i] = 0;
            else if (prob[i] > 1 - EPS) prob[i] = 1;
        }
        return prob;
    }

    private static boolean combine(List<Comp> comps, int other, int mines, double dens,
                                   boolean useMines, double[] prob, int[] g) {
        double r = dens / (1 - dens);
        double[] total = {1};
        for (Comp c : comps) total = conv(total, c.counts);
        double denomAll = 0;
        for (int t = 0; t < total.length; t++) denomAll += total[t] * weight(other, mines, r, useMines, t);
        if (!(denomAll > 0)) return false;

        for (int i = 0; i < comps.size(); i++) {
            double[] rest = {1};
            for (int j = 0; j < comps.size(); j++) if (j != i) rest = conv(rest, comps.get(j).counts);
            Comp c = comps.get(i);
            int m = c.cells.length;
            double[] W = new double[m + 1];
            for (int k = 0; k <= m; k++)
                for (int j = 0; j < rest.length; j++) W[k] += rest[j] * weight(other, mines, r, useMines, k + j);
            double den = 0;
            for (int k = 0; k <= m; k++) den += c.counts[k] * W[k];
            if (!(den > 0)) return false;
            for (int v = 0; v < m; v++) {
                double num = 0;
                for (int k = 0; k <= m; k++) num += c.cellMines[k][v] * W[k];
                prob[c.cells[v]] = num / den;
            }
        }

        double pOther;
        if (useMines) {
            if (other > 0) {
                double exp = 0;
                for (int t = 0; t < total.length; t++)
                    exp += total[t] * binom(other, mines - t) * (mines - t) / other;
                pOther = exp / denomAll;
            } else pOther = 0;
        } else pOther = dens;
        for (int i = 0; i < prob.length; i++) if (g[i] == CLOSED && Double.isNaN(prob[i])) prob[i] = pOther;
        return true;
    }

    private static double weight(int other, int mines, double r, boolean useMines, int t) {
        return useMines ? binom(other, mines - t) : Math.pow(r, t);
    }
        }
