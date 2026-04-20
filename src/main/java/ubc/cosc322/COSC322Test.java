package ubc.cosc322;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import ygraph.ai.smartfox.games.BaseGameGUI;
import ygraph.ai.smartfox.games.GameClient;
import ygraph.ai.smartfox.games.GameMessage;
import ygraph.ai.smartfox.games.GamePlayer;
import ygraph.ai.smartfox.games.amazons.AmazonsGameMessage;

public class COSC322Test extends GamePlayer {

    // Configuration
    private static final long MOVE_TIME_LIMIT_MS = 25000;
    private static final long SAFETY_MARGIN_MS   = 3000;
    private static final int  MAX_CANDIDATES     = 35;
    private static final int  SOFT_MOVE_CAP      = 15;
    private static final int  MAX_KILLER_DEPTH   = 50;

    private static final int WIN_SCORE  = Integer.MAX_VALUE - 1;
    private static final int LOSS_SCORE = Integer.MIN_VALUE + 1;

    private static final int[][] DIRECTIONS = {
        {-1,-1}, {-1,0}, {-1,1},
        { 0,-1},         { 0,1},
        { 1,-1}, { 1,0}, { 1,1}
    };

    // Game state
    private GameClient gameClient = null;
    private BaseGameGUI gamegui   = null;
    private String userName       = null;
    private String passwd         = null;
    private boolean isBlack       = false;
    private int[][] board         = null;
    private int moveCount         = 0;
    private long moveStartTime;

    // Search infrastructure
    private ExecutorService executor;
    private ConcurrentHashMap<Long, Integer> evalCache = new ConcurrentHashMap<>();

    // Zobrist hashing for eval cache
    private static final long[][][] zobristTable = new long[11][11][4];
    private static final long zobristColorKey;
    static {
        Random zRng = new Random(123456789L);
        for (int r = 0; r <= 10; r++)
            for (int c = 0; c <= 10; c++)
                for (int p = 0; p < 4; p++)
                    zobristTable[r][c][p] = zRng.nextLong();
        zobristColorKey = zRng.nextLong();
    }

    // Killer moves — two slots per depth
    private int[][] killerMoves1   = new int[MAX_KILLER_DEPTH][6];
    private int[][] killerMoves2   = new int[MAX_KILLER_DEPTH][6];
    private boolean[] killerValid1 = new boolean[MAX_KILLER_DEPTH];
    private boolean[] killerValid2 = new boolean[MAX_KILLER_DEPTH];

    // Raw bitmap constants for BFS.
    // Board is 100 bits packed into two longs: lo=bits 0-63, hi=bits 64-99.
    // Bit index = (row-1)*10 + (col-1).
    private static final long HI_MASK = (1L << 36) - 1;

    // Edge masks prevent column wrapping when shifting horizontally
    private static final long NOT_LEFT_LO, NOT_LEFT_HI;
    private static final long NOT_RIGHT_LO, NOT_RIGHT_HI;
    static {
        long nlLo = 0, nlHi = 0, nrLo = 0, nrHi = 0;
        for (int i = 0; i < 100; i++) {
            if (i % 10 != 0) {
                if (i < 64) nlLo |= (1L << i); else nlHi |= (1L << (i - 64));
            }
            if (i % 10 != 9) {
                if (i < 64) nrLo |= (1L << i); else nrHi |= (1L << (i - 64));
            }
        }
        NOT_LEFT_LO = nlLo;   NOT_LEFT_HI = nlHi;
        NOT_RIGHT_LO = nrLo;  NOT_RIGHT_HI = nrHi;
    }

    // Direction table for bitmap BFS: shift amount + direction + edge mask
    // 0=Right, 1=Left, 2=Down, 3=Up, 4=DownRight, 5=DownLeft, 6=UpRight, 7=UpLeft
    private static final int[] SHIFT_AMOUNTS  = {1, 1, 10, 10, 11, 9, 9, 11};
    private static final boolean[] SHIFT_LEFT = {true, false, true, false, true, true, false, false};

    // Pre-allocated dist arrays per thread (avoids allocation in eval)
    private static final ThreadLocal<int[]> tlDist1 = ThreadLocal.withInitial(() -> new int[100]);
    private static final ThreadLocal<int[]> tlDist2 = ThreadLocal.withInitial(() -> new int[100]);

    private class MoveResult {
        final int[] move;
        final int score;
        MoveResult(int[] move, int score) {
            this.move = move;
            this.score = score;
        }
    }

    private static class SearchResult {
        final int[] bestMove;
        final int bestScore;
        final boolean complete;
        SearchResult(int[] bestMove, int bestScore, boolean complete) {
            this.bestMove = bestMove;
            this.bestScore = bestScore;
            this.complete = complete;
        }
    }

    public static void main(String[] args) {
        COSC322Test player = new COSC322Test("pablo2", "name");

        if (player.getGameGUI() == null) {
            player.Go();
        } else {
            BaseGameGUI.sys_setup();
            java.awt.EventQueue.invokeLater(player::Go);
        }
    }

    public COSC322Test(String userName, String passwd) {
        this.userName = userName;
        this.passwd = passwd;
        this.gamegui = new BaseGameGUI(this);

        int cores = Runtime.getRuntime().availableProcessors();
        this.executor = Executors.newFixedThreadPool(cores);
        System.out.println("Initialized thread pool with " + cores + " cores.");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (executor != null && !executor.isShutdown())
                executor.shutdownNow();
        }));
    }

    @Override
    public void onLogin() {
        userName = gameClient.getUserName();
        if (getGameGUI() != null)
            getGameGUI().setRoomInformation(getGameClient().getRoomList());
    }

    @Override
    public boolean handleGameMessage(String messageType, Map<String, Object> msgDetails) {
        if (getGameGUI() == null) return true;

        if (GameMessage.GAME_STATE_BOARD.equals(messageType)) {
            handleBoardState(msgDetails);
        } else if (GameMessage.GAME_ACTION_START.equals(messageType)) {
            handleGameStart(msgDetails);
        } else if (GameMessage.GAME_ACTION_MOVE.equals(messageType)) {
            handleOpponentMove(msgDetails);
        }

        return true;
    }

    private void handleBoardState(Map<String, Object> msgDetails) {
        @SuppressWarnings("unchecked")
        ArrayList<Integer> gameState =
            (ArrayList<Integer>) msgDetails.get(AmazonsGameMessage.GAME_STATE);
        getGameGUI().setGameState(gameState);
        board = MoveGeneration.parseGameState(gameState);
    }

    private void handleGameStart(Map<String, Object> msgDetails) {
        moveStartTime = System.currentTimeMillis();

        String blackPlayer = (String) msgDetails.get(AmazonsGameMessage.PLAYER_BLACK);
        String whitePlayer = (String) msgDetails.get(AmazonsGameMessage.PLAYER_WHITE);
        isBlack = userName.equals(blackPlayer);

        System.out.println("\n========== GAME START ==========");
        System.out.println("White: " + whitePlayer + " | Black: " + blackPlayer);
        System.out.println("We are: " + (isBlack ? "Black" : "White"));
        System.out.println("================================");

        if (isBlack) {
            clearSearchTables();
            makeIntelligentMove();
        }
    }

    private void handleOpponentMove(Map<String, Object> msgDetails) {
        getGameGUI().updateGameState(msgDetails);

        if (board == null) {
            System.out.println("ERROR: Received move before board was initialized!");
            return;
        }

        @SuppressWarnings("unchecked")
        ArrayList<Integer> queenCurr = (ArrayList<Integer>) msgDetails.get(AmazonsGameMessage.QUEEN_POS_CURR);
        @SuppressWarnings("unchecked")
        ArrayList<Integer> queenNext = (ArrayList<Integer>) msgDetails.get(AmazonsGameMessage.QUEEN_POS_NEXT);
        @SuppressWarnings("unchecked")
        ArrayList<Integer> arrowPos  = (ArrayList<Integer>) msgDetails.get(AmazonsGameMessage.ARROW_POS);

        int movedPiece = board[queenCurr.get(0)][queenCurr.get(1)];
        int ourColor = isBlack ? MoveGeneration.BLACK : MoveGeneration.WHITE;
        if (movedPiece == ourColor) return;

        moveStartTime = System.currentTimeMillis();
        board = MoveGeneration.applyMove(board,
            queenCurr.get(0), queenCurr.get(1),
            queenNext.get(0), queenNext.get(1),
            arrowPos.get(0), arrowPos.get(1));

        moveCount++;
        clearSearchTables();
        makeIntelligentMove();
    }

    /*
     * Main move selection.
     * Phase 1: shallow territory eval on all moves, sort best-first.
     * Phase 2: iterative deepening on top candidates until time runs out.
     */
    private void makeIntelligentMove() {
        int color = isBlack ? MoveGeneration.BLACK : MoveGeneration.WHITE;
        List<int[]> moves = MoveGeneration.getAllMoves(board, color);

        if (moves.isEmpty()) {
            System.out.println("No moves available — we lost!");
            return;
        }

        int totalMoves = moves.size();
        System.out.println("\n--- Move " + (moveCount + 1) + " ("
            + (isBlack ? "Black" : "White") + ") ---");
        System.out.println("Legal moves: " + totalMoves);
        List<int[]> scoredMoves = shallowEvalAllMoves(moves, color);

        int[] bestMove = scoredMoves.get(0);
        int bestScore = bestMove[6];
        int bestDepth = 1;

        int emptySquares = countEmptySquares();
        int maxUsefulDepth = Math.min(emptySquares, MAX_KILLER_DEPTH - 1);

        int numCandidates = Math.min(MAX_CANDIDATES, scoredMoves.size());
        List<int[]> candidates = new ArrayList<>(numCandidates);
        for (int i = 0; i < numCandidates; i++) {
            int[] sm = scoredMoves.get(i);
            candidates.add(new int[]{sm[0], sm[1], sm[2], sm[3], sm[4], sm[5]});
        }

        boolean positionSolved = false;

        for (int depth = 2; depth <= maxUsefulDepth; depth++) {
            if (isTimeUp()) break;

            SearchResult result = searchAtDepth(candidates, depth, color);

            if (result.complete) {
                bestMove = result.bestMove;
                bestScore = result.bestScore;
                bestDepth = depth;
                System.out.println("  -> Complete | Best score: " + bestScore);

                if (bestScore >= WIN_SCORE) {
                    System.out.println("  -> FORCED WIN! Stopping search.");
                    positionSolved = true;
                    break;
                }
                if (bestScore <= LOSS_SCORE) {
                    System.out.println("  -> FORCED LOSS. Stopping search.");
                    positionSolved = true;
                    break;
                }
            } else {
                System.out.println("  -> Timeout");
                break;
            }
        }

        long elapsed = System.currentTimeMillis() - moveStartTime;
        System.out.println("\nMove: (" + bestMove[0] + "," + bestMove[1] + ")->("
            + bestMove[2] + "," + bestMove[3] + ") arrow(" + bestMove[4] + "," + bestMove[5]
            + ") | Score: " + bestScore + " | Depth: " + bestDepth
            + " | Time: " + elapsed + "ms"
            + (positionSolved ? " | SOLVED" : ""));

        sendMove(bestMove, color);
    }

    private List<int[]> shallowEvalAllMoves(List<int[]> moves, int color) {
        List<int[]> scored = new ArrayList<>(moves.size());
        int total = moves.size();
        int lastPercent = -1;

        printProgressBar("Depth 1", 0, total);

        for (int i = 0; i < total; i++) {
            if (isTimeUp()) break;

            int[] move = moves.get(i);
            int[][] newBoard = MoveGeneration.applyMove(board,
                move[0], move[1], move[2], move[3], move[4], move[5]);
            int score = evaluateBoard(newBoard, color);
            scored.add(new int[]{move[0], move[1], move[2], move[3], move[4], move[5], score});

            int percent = (int)(((i + 1) / (double) total) * 100);
            if (percent / 5 > lastPercent / 5 || i == total - 1) {
                lastPercent = percent;
                printProgressBar("Depth 1", i + 1, total);
            }
        }

        scored.sort((a, b) -> Integer.compare(b[6], a[6]));

        printProgressBar("Depth 1", scored.size(), total);
        System.out.println();
        if (!scored.isEmpty())
            System.out.println("  -> Complete | Best score: " + scored.get(0)[6]);

        return scored;
    }

    private SearchResult searchAtDepth(List<int[]> candidates, int depth, int color) {
        int depthBestScore = Integer.MIN_VALUE;
        int[] depthBestMove = candidates.get(0);
        boolean depthComplete = true;

        String label = "Depth " + depth;
        int total = candidates.size();

        printProgressBar(label, 0, total);

        AtomicInteger globalAlpha = new AtomicInteger(Integer.MIN_VALUE);
        List<Future<MoveResult>> futures = new ArrayList<>(candidates.size());

        for (int[] move : candidates) {
            if (isTimeUp()) { depthComplete = false; break; }

            int[][] newBoard = MoveGeneration.applyMove(board,
                move[0], move[1], move[2], move[3], move[4], move[5]);
            final int d = depth;

            Callable<MoveResult> task = () -> {
                int a = globalAlpha.get();
                int score = minimaxAlphaBeta(newBoard, d, a, Integer.MAX_VALUE, false, color, globalAlpha);
                updateGlobalAlpha(globalAlpha, score);
                return new MoveResult(move, score);
            };

            futures.add(executor.submit(task));
        }

        int completedCount = 0;
        for (Future<MoveResult> future : futures) {
            if (isTimeUp()) { depthComplete = false; break; }

            try {
                while (!future.isDone()) {
                    if (isTimeUp()) { depthComplete = false; break; }
                    Thread.sleep(5);
                }
                if (!depthComplete) break;

                MoveResult result = future.get();
                if (result.score > depthBestScore) {
                    depthBestScore = result.score;
                    depthBestMove = result.move;
                }

                completedCount++;
                printProgressBar(label, completedCount, total);
            } catch (Exception e) {
                System.err.println("Thread error: " + e.getMessage());
            }
        }

        printProgressBar(label, completedCount, total);
        System.out.println();

        if (!depthComplete) {
            for (Future<MoveResult> f : futures)
                f.cancel(true);
        }

        return new SearchResult(depthBestMove, depthBestScore, depthComplete && !isTimeUp());
    }

    /*
     * Territory evaluation using raw bitmap BFS.
     * Converts board to two longs (lo/hi), runs BFS with native bit shifts.
     * All squares in a direction are shifted simultaneously in ~2 CPU instructions
     * instead of looping per square.
     */
    private int evaluateBoard(int[][] boardState, int color) {
        long hash = computeZobristHash(boardState, color);

        Integer cached = evalCache.get(hash);
        if (cached != null) return cached;

        int oppColor = (color == MoveGeneration.BLACK)
            ? MoveGeneration.WHITE : MoveGeneration.BLACK;

        // Convert board to bitmasks
        long emptyLo = 0, emptyHi = 0;
        long myLo = 0, myHi = 0;
        long oppLo = 0, oppHi = 0;

        for (int r = 1; r <= 10; r++) {
            for (int c = 1; c <= 10; c++) {
                int bit = (r - 1) * 10 + (c - 1);
                int piece = boardState[r][c];
                if (piece == MoveGeneration.EMPTY) {
                    if (bit < 64) emptyLo |= (1L << bit); else emptyHi |= (1L << (bit - 64));
                } else if (piece == color) {
                    if (bit < 64) myLo |= (1L << bit); else myHi |= (1L << (bit - 64));
                } else if (piece == oppColor) {
                    if (bit < 64) oppLo |= (1L << bit); else oppHi |= (1L << (bit - 64));
                }
            }
        }

        int[] myDist = tlDist1.get();
        int[] oppDist = tlDist2.get();
        bitmapBFS(myLo, myHi, emptyLo, emptyHi, myDist);
        bitmapBFS(oppLo, oppHi, emptyLo, emptyHi, oppDist);

        int score = 0;
        for (int i = 0; i < 64; i++) {
            if ((emptyLo & (1L << i)) == 0) continue;
            int md = myDist[i], od = oppDist[i];
            if (md < od) { score++; if (od - md >= 2) score++; }
            else if (od < md) { score--; if (md - od >= 2) score--; }
        }
        for (int i = 0; i < 36; i++) {
            if ((emptyHi & (1L << i)) == 0) continue;
            int md = myDist[i + 64], od = oppDist[i + 64];
            if (md < od) { score++; if (od - md >= 2) score++; }
            else if (od < md) { score--; if (md - od >= 2) score--; }
        }

        evalCache.put(hash, score);
        return score;
    }

    // Shift 100-bit value left by n (toward higher bits)
    private static long[] shl(long lo, long hi, int n) {
        if (n >= 64) return new long[]{0, (lo << (n - 64)) & HI_MASK};
        return new long[]{lo << n, ((hi << n) | (lo >>> (64 - n))) & HI_MASK};
    }

    // Shift 100-bit value right by n (toward lower bits)
    private static long[] shr(long lo, long hi, int n) {
        if (n >= 64) return new long[]{hi >>> (n - 64), 0};
        return new long[]{(lo >>> n) | (hi << (64 - n)), hi >>> n};
    }

    // Edge mask for a direction: prevents column wrapping on horizontal shifts
    private static long[] edgeMask(int dir) {
        switch (dir) {
            case 0: case 4: case 6: return new long[]{NOT_RIGHT_LO, NOT_RIGHT_HI};
            case 1: case 5: case 7: return new long[]{NOT_LEFT_LO, NOT_LEFT_HI};
            default: return new long[]{~0L, HI_MASK};
        }
    }

    /*
     * Bitmap BFS from all queens simultaneously.
     * Each BFS step slides the frontier in all 8 directions using bulk bit shifts.
     * One shift + AND + OR processes all frontier squares at once.
     */
    private void bitmapBFS(long queenLo, long queenHi, long emptyLo, long emptyHi, int[] dist) {
        Arrays.fill(dist, Integer.MAX_VALUE);

        // Seed queens at distance 0
        for (int i = 0; i < 64; i++)
            if ((queenLo & (1L << i)) != 0) dist[i] = 0;
        for (int i = 0; i < 36; i++)
            if ((queenHi & (1L << i)) != 0) dist[i + 64] = 0;

        long reachedLo = queenLo, reachedHi = queenHi;
        long frontierLo = queenLo, frontierHi = queenHi;

        for (int step = 1; (frontierLo | frontierHi) != 0; step++) {
            long newFrLo = 0, newFrHi = 0;

            for (int d = 0; d < 8; d++) {
                int amount = SHIFT_AMOUNTS[d];
                boolean isLeft = SHIFT_LEFT[d];
                long[] em = edgeMask(d);

                long sLo = frontierLo, sHi = frontierHi;

                // Slide along direction until no new squares found
                while ((sLo | sHi) != 0) {
                    sLo &= em[0];
                    sHi &= em[1];

                    long[] sh = isLeft ? shl(sLo, sHi, amount) : shr(sLo, sHi, amount);

                    sh[0] &= emptyLo & ~reachedLo;
                    sh[1] &= emptyHi & ~reachedHi;

                    newFrLo |= sh[0];
                    newFrHi |= sh[1];

                    sLo = sh[0];
                    sHi = sh[1];
                }
            }

            // Record distances
            for (int i = 0; i < 64; i++)
                if ((newFrLo & (1L << i)) != 0) dist[i] = step;
            for (int i = 0; i < 36; i++)
                if ((newFrHi & (1L << i)) != 0) dist[i + 64] = step;

            reachedLo |= newFrLo;
            reachedHi |= newFrHi;
            frontierLo = newFrLo;
            frontierHi = newFrHi;
        }
    }

    /*
     * Alpha-beta minimax with move ordering.
     * sharedAlpha lets parallel threads tighten each other's pruning window.
     */
    private int minimaxAlphaBeta(int[][] boardState, int depth, int alpha, int beta,
                                  boolean maximizing, int color, AtomicInteger sharedAlpha) {
        if (isTimeUp()) return 0;

        if (depth == 0)
            return evaluateBoard(boardState, color);

        // Tighten alpha from other threads' discoveries
        if (sharedAlpha != null) {
            int shared = sharedAlpha.get();
            if (shared > alpha) alpha = shared;
            if (beta <= alpha) return alpha;
        }

        int currentColor = maximizing ? color
            : (color == MoveGeneration.BLACK ? MoveGeneration.WHITE : MoveGeneration.BLACK);
        int oppColor = (currentColor == MoveGeneration.BLACK)
            ? MoveGeneration.WHITE : MoveGeneration.BLACK;

        List<int[]> moves = MoveGeneration.getAllMoves(boardState, currentColor);

        if (moves.isEmpty())
            return maximizing ? LOSS_SCORE : WIN_SCORE;

        List<int[]> orderedMoves = orderMoves(moves, boardState, oppColor, depth);

        if (maximizing) {
            int maxEval = Integer.MIN_VALUE;
            for (int[] move : orderedMoves) {
                if (isTimeUp()) break;
                int[][] newBoard = MoveGeneration.applyMove(boardState,
                    move[0], move[1], move[2], move[3], move[4], move[5]);
                int eval = minimaxAlphaBeta(newBoard, depth - 1, alpha, beta, false, color, null);
                maxEval = Math.max(maxEval, eval);
                alpha = Math.max(alpha, eval);
                if (beta <= alpha) {
                    storeKillerMove(Math.min(depth, MAX_KILLER_DEPTH - 1), move);
                    break;
                }
            }
            return maxEval;
        } else {
            int minEval = Integer.MAX_VALUE;
            for (int[] move : orderedMoves) {
                if (isTimeUp()) break;
                int[][] newBoard = MoveGeneration.applyMove(boardState,
                    move[0], move[1], move[2], move[3], move[4], move[5]);
                int eval = minimaxAlphaBeta(newBoard, depth - 1, alpha, beta, true, color, null);
                minEval = Math.min(minEval, eval);
                beta = Math.min(beta, eval);
                if (beta <= alpha) {
                    storeKillerMove(Math.min(depth, MAX_KILLER_DEPTH - 1), move);
                    break;
                }
            }
            return minEval;
        }
    }

    // Move ordering: killer moves first, then cheap heuristic (centrality + arrow aggression)
    private List<int[]> orderMoves(List<int[]> moves, int[][] boardState,
                                    int oppColor, int depth) {
        int depthIndex = Math.min(depth, MAX_KILLER_DEPTH - 1);
        int moveCnt = moves.size();

        int ki1 = findKillerIndex(moves, killerMoves1[depthIndex], killerValid1[depthIndex]);
        int ki2 = findKillerIndex(moves, killerMoves2[depthIndex], killerValid2[depthIndex]);
        if (ki2 == ki1) ki2 = -1;

        List<int[]> ordered = new ArrayList<>(Math.min(moveCnt, SOFT_MOVE_CAP + 2));
        if (ki1 >= 0) ordered.add(moves.get(ki1));
        if (ki2 >= 0) ordered.add(moves.get(ki2));

        // Locate opponent queens
        int numOpp = 0;
        int[] oqR = new int[4], oqC = new int[4];
        for (int r = 1; r <= 10; r++)
            for (int c = 1; c <= 10; c++)
                if (boardState[r][c] == oppColor && numOpp < 4) {
                    oqR[numOpp] = r;
                    oqC[numOpp] = c;
                    numOpp++;
                }

        // Score non-killer moves
        int nonKillerCount = moveCnt - ordered.size();
        int[] scoredFlat = new int[nonKillerCount * 2];
        int flatIdx = 0;

        for (int i = 0; i < moveCnt; i++) {
            if (i == ki1 || i == ki2) continue;

            int[] m = moves.get(i);
            int hScore = 0;

            // Queen centrality
            hScore += 20 - (Math.abs(2 * m[2] - 11) + Math.abs(2 * m[3] - 11));

            // Arrow near opponent queens
            for (int q = 0; q < numOpp; q++) {
                int dist = Math.max(Math.abs(m[4] - oqR[q]), Math.abs(m[5] - oqC[q]));
                if (dist <= 2) hScore += 15;
                if (dist == 1) hScore += 20;
            }

            scoredFlat[flatIdx]     = i;
            scoredFlat[flatIdx + 1] = hScore;
            flatIdx += 2;
        }

        // Insertion sort
        int pairCount = flatIdx / 2;
        for (int i = 1; i < pairCount; i++) {
            int tmpIdx   = scoredFlat[i * 2];
            int tmpScore = scoredFlat[i * 2 + 1];
            int j = i - 1;
            while (j >= 0 && scoredFlat[j * 2 + 1] < tmpScore) {
                scoredFlat[(j + 1) * 2]     = scoredFlat[j * 2];
                scoredFlat[(j + 1) * 2 + 1] = scoredFlat[j * 2 + 1];
                j--;
            }
            scoredFlat[(j + 1) * 2]     = tmpIdx;
            scoredFlat[(j + 1) * 2 + 1] = tmpScore;
        }

        int cap = Math.min(SOFT_MOVE_CAP, pairCount);
        for (int i = 0; i < cap; i++)
            ordered.add(moves.get(scoredFlat[i * 2]));

        return ordered;
    }

    private int findKillerIndex(List<int[]> moves, int[] killer, boolean valid) {
        if (!valid) return -1;
        for (int i = 0; i < moves.size(); i++) {
            int[] m = moves.get(i);
            if (m[0] == killer[0] && m[1] == killer[1] && m[2] == killer[2]
                    && m[3] == killer[3] && m[4] == killer[4] && m[5] == killer[5])
                return i;
        }
        return -1;
    }

    private void storeKillerMove(int depthIndex, int[] move) {
        if (killerValid1[depthIndex] && movesMatch(move, killerMoves1[depthIndex]))
            return;
        if (killerValid1[depthIndex]) {
            System.arraycopy(killerMoves1[depthIndex], 0, killerMoves2[depthIndex], 0, 6);
            killerValid2[depthIndex] = true;
        }
        System.arraycopy(move, 0, killerMoves1[depthIndex], 0, 6);
        killerValid1[depthIndex] = true;
    }

    private boolean movesMatch(int[] a, int[] b) {
        return a[0] == b[0] && a[1] == b[1] && a[2] == b[2]
            && a[3] == b[3] && a[4] == b[4] && a[5] == b[5];
    }

    // Utility methods

    private long computeZobristHash(int[][] boardState, int color) {
        long hash = 0L;
        for (int r = 1; r <= 10; r++)
            for (int c = 1; c <= 10; c++) {
                int piece = boardState[r][c];
                if (piece != 0) hash ^= zobristTable[r][c][piece];
            }
        if (color == MoveGeneration.BLACK) hash ^= zobristColorKey;
        return hash;
    }

    private void clearSearchTables() {
        killerMoves1  = new int[MAX_KILLER_DEPTH][6];
        killerMoves2  = new int[MAX_KILLER_DEPTH][6];
        killerValid1  = new boolean[MAX_KILLER_DEPTH];
        killerValid2  = new boolean[MAX_KILLER_DEPTH];
        evalCache.clear();
    }

    private boolean isTimeUp() {
        return (System.currentTimeMillis() - moveStartTime) >= (MOVE_TIME_LIMIT_MS - SAFETY_MARGIN_MS);
    }

    private int countEmptySquares() {
        int count = 0;
        for (int r = 1; r <= 10; r++)
            for (int c = 1; c <= 10; c++)
                if (board[r][c] == MoveGeneration.EMPTY) count++;
        return count;
    }

    private void updateGlobalAlpha(AtomicInteger globalAlpha, int score) {
        int currentVal;
        do {
            currentVal = globalAlpha.get();
            if (score <= currentVal) break;
        } while (!globalAlpha.compareAndSet(currentVal, score));
    }

    private void sendMove(int[] move, int color) {
        int fromRow = move[0], fromCol = move[1];
        int toRow   = move[2], toCol   = move[3];
        int arrowR  = move[4], arrowC  = move[5];

        if (board[fromRow][fromCol] != color)
            System.out.println("ERROR: Moving piece " + board[fromRow][fromCol]
                + " at (" + fromRow + "," + fromCol + ") but we are color " + color);
        if (board[toRow][toCol] != MoveGeneration.EMPTY)
            System.out.println("ERROR: Destination (" + toRow + "," + toCol
                + ") occupied by " + board[toRow][toCol]);

        ArrayList<Integer> currPos  = new ArrayList<>(Arrays.asList(fromRow, fromCol));
        ArrayList<Integer> newPos   = new ArrayList<>(Arrays.asList(toRow, toCol));
        ArrayList<Integer> arrowPos = new ArrayList<>(Arrays.asList(arrowR, arrowC));

        getGameClient().sendMoveMessage(currPos, newPos, arrowPos);
        getGameGUI().updateGameState(currPos, newPos, arrowPos);

        board = MoveGeneration.applyMove(board, fromRow, fromCol, toRow, toCol, arrowR, arrowC);
        moveCount++;
    }

    private void printProgressBar(String label, int current, int total) {
        int barWidth = 30;
        int filled = (total > 0) ? (int)((current / (double) total) * barWidth) : 0;
        int percent = (total > 0) ? (int)((current / (double) total) * 100) : 0;

        StringBuilder bar = new StringBuilder("\r" + label + ": [");
        for (int j = 0; j < barWidth; j++)
            bar.append(j < filled ? '=' : ' ');
        bar.append("] ").append(current).append("/").append(total)
           .append(" (").append(percent).append("%)   ");

        System.out.print(bar);
        System.out.flush();
    }

    @Override public String userName()         { return userName; }
    @Override public GameClient getGameClient() { return this.gameClient; }
    @Override public BaseGameGUI getGameGUI()   { return this.gamegui; }
    @Override public void connect()             { gameClient = new GameClient(userName, passwd, this); }
}