package ubc.cosc322;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;

import ygraph.ai.smartfox.games.BaseGameGUI;
import ygraph.ai.smartfox.games.GameClient;
import ygraph.ai.smartfox.games.GameMessage;
import ygraph.ai.smartfox.games.GamePlayer;
import ygraph.ai.smartfox.games.amazons.AmazonsGameMessage;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

public class COSC322Test extends GamePlayer {

    private GameClient gameClient = null;
    private BaseGameGUI gamegui = null;

    private String userName = null;
    private String passwd = null;

    private boolean isBlack = false;
    private int[][] board;

    private int moveCount = 0;

    private static final long MOVE_TIME_LIMIT_MS = 30000;
    private static final long SAFETY_MARGIN_MS = 3000;
    private long moveStartTime;

    private ExecutorService executor;

    // =============================================
    // Zobrist Hashing
    // =============================================
    private static final long[][][] zobristTable = new long[11][11][4];
    private static final long zobristColorKey;
    static {
        Random zRng = new Random(123456789L);
        for (int r = 0; r <= 10; r++) {
            for (int c = 0; c <= 10; c++) {
                for (int p = 0; p < 4; p++) {
                    zobristTable[r][c][p] = zRng.nextLong();
                }
            }
        }
        zobristColorKey = zRng.nextLong();
    }

    private java.util.concurrent.ConcurrentHashMap<Long, Integer> evalCache =
        new java.util.concurrent.ConcurrentHashMap<>();

    // =============================================
    // Killer Moves — two slots per depth
    // =============================================
    private static final int MAX_KILLER_DEPTH = 50;
    private int[][] killerMoves1 = new int[MAX_KILLER_DEPTH][6];
    private int[][] killerMoves2 = new int[MAX_KILLER_DEPTH][6];
    private boolean[] killerValid1 = new boolean[MAX_KILLER_DEPTH];
    private boolean[] killerValid2 = new boolean[MAX_KILLER_DEPTH];

    // =============================================
    // BFS directions (shared constant)
    // =============================================
    private static final int[][] DIRECTIONS = {
        {-1,-1},{-1,0},{-1,1},
        {0,-1},{0,1},
        {1,-1},{1,0},{1,1}
    };

    private class MoveResult {
        int[] move;
        int score;
        public MoveResult(int[] move, int score) {
            this.move = move;
            this.score = score;
        }
    }

    public static void main(String[] args) {
        COSC322Test player = new COSC322Test("pablo123", "name");

        if (player.getGameGUI() == null) {
            player.Go();
        } else {
            BaseGameGUI.sys_setup();
            java.awt.EventQueue.invokeLater(() -> player.Go());
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
            if (executor != null && !executor.isShutdown()) {
                executor.shutdownNow();
            }
        }));
    }

    // =============================================
    // Zobrist hash
    // =============================================
    private long computeZobristHash(int[][] boardState, int color) {
        long hash = 0L;
        for (int r = 1; r <= 10; r++) {
            for (int c = 1; c <= 10; c++) {
                int piece = boardState[r][c];
                if (piece != 0) {
                    hash ^= zobristTable[r][c][piece];
                }
            }
        }
        if (color == MoveGeneration.BLACK) {
            hash ^= zobristColorKey;
        }
        return hash;
    }

    private void clearSearchTables() {
        killerMoves1 = new int[MAX_KILLER_DEPTH][6];
        killerMoves2 = new int[MAX_KILLER_DEPTH][6];
        killerValid1 = new boolean[MAX_KILLER_DEPTH];
        killerValid2 = new boolean[MAX_KILLER_DEPTH];
        evalCache.clear();
    }

    // =============================================
    // Server message handling
    // =============================================
    @Override
    public void onLogin() {
        userName = gameClient.getUserName();
        if (getGameGUI() != null) {
            getGameGUI().setRoomInformation(getGameClient().getRoomList());
        }
    }

    @Override
    public boolean handleGameMessage(String messageType, Map<String, Object> msgDetails) {
        if (getGameGUI() == null) return true;

        if (GameMessage.GAME_STATE_BOARD.equals(messageType)) {
            ArrayList<Integer> gameState =
                (ArrayList<Integer>) msgDetails.get(AmazonsGameMessage.GAME_STATE);
            getGameGUI().setGameState(gameState);
            board = MoveGeneration.parseGameState(gameState);

        } else if (GameMessage.GAME_ACTION_START.equals(messageType)) {
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

        } else if (GameMessage.GAME_ACTION_MOVE.equals(messageType)) {
            getGameGUI().updateGameState(msgDetails);

            ArrayList<Integer> queenCurr = (ArrayList<Integer>) msgDetails.get(AmazonsGameMessage.QUEEN_POS_CURR);
            ArrayList<Integer> queenNext = (ArrayList<Integer>) msgDetails.get(AmazonsGameMessage.QUEEN_POS_NEXT);
            ArrayList<Integer> arrowPos = (ArrayList<Integer>) msgDetails.get(AmazonsGameMessage.ARROW_POS);

            int movedPiece = board[queenCurr.get(0)][queenCurr.get(1)];
            int ourColor = isBlack ? MoveGeneration.BLACK : MoveGeneration.WHITE;

            if (movedPiece == ourColor) {
                return true;
            }

            moveStartTime = System.currentTimeMillis();

            board = MoveGeneration.applyMove(board,
                queenCurr.get(0), queenCurr.get(1),
                queenNext.get(0), queenNext.get(1),
                arrowPos.get(0), arrowPos.get(1));

            moveCount++;
            clearSearchTables();
            makeIntelligentMove();
        }

        return true;
    }

    // =============================================
    // Top-level move selection
    // Tighter candidate count (paper recommends 10-15, we use 35
    // as a compromise since we have threading to help)
    // =============================================
    private static final int MAX_CANDIDATES = 35;

    private void makeIntelligentMove() {
        int color = isBlack ? MoveGeneration.BLACK : MoveGeneration.WHITE;
        List<int[]> moves = MoveGeneration.getAllMoves(board, color);

        if (moves.isEmpty()) {
            System.out.println("No moves available — we lost!");
            return;
        }

        int totalMoves = moves.size();
        System.out.println("\n--- Move " + (moveCount + 1) + " (" + (isBlack ? "Black" : "White") + ") ---");
        System.out.println("Legal moves: " + totalMoves);

        // =============================================
        // Phase 1: Shallow territory eval to rank all moves
        // Much faster now — just two BFS passes per board, no move generation
        // =============================================
        List<int[]> scoredMoves = new ArrayList<>();
        int shallowCount = 0;

        System.out.print("Sorting moves (depth 0)... ");
        for (int i = 0; i < moves.size(); i++) {
            if (isTimeUp()) break;

            int[] move = moves.get(i);
            int[][] newBoard = MoveGeneration.applyMove(board,
                    move[0], move[1], move[2], move[3], move[4], move[5]);

            int score = evaluateBoard(newBoard, color);

            scoredMoves.add(new int[]{move[0], move[1], move[2], move[3], move[4], move[5], score});
            shallowCount++;
        }

        scoredMoves.sort((a, b) -> Integer.compare(b[6], a[6]));

        int[] bestMove = scoredMoves.get(0);
        int bestScore = bestMove[6];
        int bestDepth = 0;

        long shallowTime = System.currentTimeMillis() - moveStartTime;
        System.out.println(shallowCount + "/" + totalMoves + " in " + shallowTime + "ms");

        // =============================================
        // Phase 2: Iterative deepening on top candidates
        // =============================================
        int numCandidates = Math.min(MAX_CANDIDATES, scoredMoves.size());

        List<int[]> candidates = new ArrayList<>();
        for (int i = 0; i < numCandidates; i++) {
            int[] sm = scoredMoves.get(i);
            candidates.add(new int[]{sm[0], sm[1], sm[2], sm[3], sm[4], sm[5]});
        }

        for (int depth = 2; ; depth++) {
            if (isTimeUp()) break;

            int depthBestScore = Integer.MIN_VALUE;
            int[] depthBestMove = candidates.get(0);
            boolean depthComplete = true;

            System.out.print("Depth " + depth + ": ");

            AtomicInteger globalAlpha = new AtomicInteger(Integer.MIN_VALUE);
            List<Future<MoveResult>> futures = new ArrayList<>();

            for (int i = 0; i < candidates.size(); i++) {
                if (isTimeUp()) {
                    depthComplete = false;
                    break;
                }

                int[] move = candidates.get(i);
                int[][] newBoard = MoveGeneration.applyMove(board,
                        move[0], move[1], move[2], move[3], move[4], move[5]);

                final int currentDepth = depth;

                Callable<MoveResult> task = () -> {
                    int currentAlpha = globalAlpha.get();
                    int score = minimaxAlphaBeta(newBoard, currentDepth, currentAlpha,
                            Integer.MAX_VALUE, false, color);
                    updateGlobalAlpha(globalAlpha, score);
                    return new MoveResult(move, score);
                };

                futures.add(executor.submit(task));
            }

            int completedCount = 0;
            for (Future<MoveResult> future : futures) {
                if (isTimeUp()) {
                    depthComplete = false;
                    break;
                }

                try {
                    while (!future.isDone()) {
                        if (isTimeUp()) {
                            depthComplete = false;
                            break;
                        }
                        Thread.sleep(5);
                    }

                    if (!depthComplete) break;

                    MoveResult result = future.get();
                    if (result.score > depthBestScore) {
                        depthBestScore = result.score;
                        depthBestMove = result.move;
                    }

                    completedCount++;
                    printProgress(depth, completedCount, candidates.size());

                } catch (Exception e) {
                    System.err.println("Thread interrupted during evaluation!");
                }
            }

            System.out.println();

            if (depthComplete && !isTimeUp()) {
                bestMove = depthBestMove;
                bestScore = depthBestScore;
                bestDepth = depth;
                System.out.println("  -> Complete | Best score: " + bestScore);
            } else {
                System.out.println("  -> Incomplete (timeout), discarding partial results");
                for (Future<MoveResult> f : futures) {
                    f.cancel(true);
                }
                break;
            }
        }

        long elapsed = System.currentTimeMillis() - moveStartTime;
        System.out.println("Best: (" + bestMove[0] + "," + bestMove[1] + ")->(" 
            + bestMove[2] + "," + bestMove[3] + ") arrow(" + bestMove[4] + "," + bestMove[5] 
            + ") | Score: " + bestScore + " | Depth: " + bestDepth + " | Time: " + elapsed + "ms");

        int fromRow = bestMove[0], fromCol = bestMove[1];
        int toRow = bestMove[2], toCol = bestMove[3];
        int arrowRow = bestMove[4], arrowCol = bestMove[5];

        // Validation
        int piece = board[fromRow][fromCol];
        if (piece != color) {
            System.out.println("ERROR: Trying to move piece " + piece + " at (" + fromRow + "," + fromCol 
                + ") but we are color " + color + "!");
        }
        if (board[toRow][toCol] != MoveGeneration.EMPTY) {
            System.out.println("ERROR: Destination (" + toRow + "," + toCol + ") is not empty!");
        }

        ArrayList<Integer> queenPosCurrent = new ArrayList<>(Arrays.asList(fromRow, fromCol));
        ArrayList<Integer> queenPosNew = new ArrayList<>(Arrays.asList(toRow, toCol));
        ArrayList<Integer> arrowPos = new ArrayList<>(Arrays.asList(arrowRow, arrowCol));

        getGameClient().sendMoveMessage(queenPosCurrent, queenPosNew, arrowPos);
        getGameGUI().updateGameState(queenPosCurrent, queenPosNew, arrowPos);

        board = MoveGeneration.applyMove(board, fromRow, fromCol, toRow, toCol, arrowRow, arrowCol);
        moveCount++;
    }

    private boolean isTimeUp() {
        return (System.currentTimeMillis() - moveStartTime) >= (MOVE_TIME_LIMIT_MS - SAFETY_MARGIN_MS);
    }

    private void printProgress(int depth, int current, int total) {
        int barWidth = 30;
        int filled = (int)((current / (double) total) * barWidth);
        int percent = (int)((current / (double) total) * 100);

        StringBuilder bar = new StringBuilder("\rDepth " + depth + ": [");
        for (int j = 0; j < barWidth; j++) {
            bar.append(j < filled ? '=' : ' ');
        }
        bar.append("] ").append(current).append("/").append(total)
           .append(" (").append(percent).append("%)");

        System.out.print(bar);
    }

    // =============================================
    // EVALUATION — Territory only (paper's recommendation)
    //
    // The paper found that min-distance (Voronoi territory) alone,
    // without mobility/freedom/centralization, produced stronger play
    // because the faster eval allows deeper search.
    //
    // For each empty square: whoever can reach it in fewer queen moves
    // (via BFS respecting obstacles) owns it.
    // Score = (my squares) - (opponent squares)
    // =============================================
    private int evaluateBoard(int[][] boardState, int color) {
        long boardHash = computeZobristHash(boardState, color);

        Integer cached = evalCache.get(boardHash);
        if (cached != null) {
            return cached;
        }

        int oppColor = (color == MoveGeneration.BLACK)
                ? MoveGeneration.WHITE : MoveGeneration.BLACK;

        int[][] myDist = bfsQueenDistance(boardState, color);
        int[][] oppDist = bfsQueenDistance(boardState, oppColor);

        int score = 0;

        for (int r = 1; r <= 10; r++) {
            for (int c = 1; c <= 10; c++) {
                if (boardState[r][c] != 0) continue;

                int md = myDist[r][c];
                int od = oppDist[r][c];

                if (md < od) score++;
                else if (od < md) score--;

                // Bonus: if we're 2+ moves closer, the square is very safe
                // This adds gradient information beyond just counting squares
                if (md < od && od - md >= 2) score++;
                if (od < md && md - od >= 2) score--;
            }
        }

        evalCache.put(boardHash, score);
        return score;
    }

    // =============================================
    // BFS from all queens of a color simultaneously
    // Uses flat arrays instead of LinkedList for efficiency
    // =============================================
    private int[][] bfsQueenDistance(int[][] board, int color) {
        int[][] dist = new int[11][11];
        for (int r = 1; r <= 10; r++) {
            for (int c = 1; c <= 10; c++) {
                dist[r][c] = Integer.MAX_VALUE;
            }
        }

        int[] queueR = new int[800];
        int[] queueC = new int[800];
        int qHead = 0, qTail = 0;

        for (int r = 1; r <= 10; r++) {
            for (int c = 1; c <= 10; c++) {
                if (board[r][c] == color) {
                    dist[r][c] = 0;
                    queueR[qTail] = r;
                    queueC[qTail] = c;
                    qTail++;
                }
            }
        }

        while (qHead < qTail) {
            int r = queueR[qHead];
            int c = queueC[qHead];
            qHead++;

            int nextDist = dist[r][c] + 1;

            for (int[] d : DIRECTIONS) {
                int nr = r + d[0];
                int nc = c + d[1];

                while (nr >= 1 && nr <= 10 && nc >= 1 && nc <= 10
                        && board[nr][nc] == 0) {
                    if (nextDist < dist[nr][nc]) {
                        dist[nr][nc] = nextDist;
                        if (qTail < queueR.length) {
                            queueR[qTail] = nr;
                            queueC[qTail] = nc;
                            qTail++;
                        }
                    }
                    nr += d[0];
                    nc += d[1];
                }
            }
        }

        return dist;
    }

    // =============================================
    // Minimax with alpha-beta pruning
    // Killer moves + cheap heuristic ordering
    // No hard cap — good ordering lets alpha-beta prune naturally
    // Soft safety cap at 80
    // =============================================
    private int minimaxAlphaBeta(int[][] boardState, int depth, int alpha, int beta,
                                  boolean maximizingPlayer, int color) {
        if (isTimeUp()) return 0;

        // Leaf node — fast territory-only eval
        if (depth == 0) {
            return evaluateBoard(boardState, color);
        }

        int currentColor = maximizingPlayer ? color :
            (color == MoveGeneration.BLACK ? MoveGeneration.WHITE : MoveGeneration.BLACK);
        int oppColor = (currentColor == MoveGeneration.BLACK)
            ? MoveGeneration.WHITE : MoveGeneration.BLACK;

        List<int[]> moves = MoveGeneration.getAllMoves(boardState, currentColor);

        if (moves.isEmpty()) {
            return maximizingPlayer ? Integer.MIN_VALUE + 1 : Integer.MAX_VALUE - 1;
        }

        int depthIndex = Math.min(depth, MAX_KILLER_DEPTH - 1);
        int moveCount = moves.size();

        // =============================================
        // Move ordering: killers first, then cheap heuristic
        // =============================================

        // Step 1: Find killers by index
        int killer1Index = -1;
        int killer2Index = -1;
        if (killerValid1[depthIndex]) {
            int[] k1 = killerMoves1[depthIndex];
            for (int i = 0; i < moveCount; i++) {
                int[] m = moves.get(i);
                if (m[0] == k1[0] && m[1] == k1[1] && m[2] == k1[2]
                        && m[3] == k1[3] && m[4] == k1[4] && m[5] == k1[5]) {
                    killer1Index = i;
                    break;
                }
            }
        }
        if (killerValid2[depthIndex]) {
            int[] k2 = killerMoves2[depthIndex];
            for (int i = 0; i < moveCount; i++) {
                int[] m = moves.get(i);
                if (m[0] == k2[0] && m[1] == k2[1] && m[2] == k2[2]
                        && m[3] == k2[3] && m[4] == k2[4] && m[5] == k2[5]) {
                    killer2Index = i;
                    break;
                }
            }
        }

        // Step 2: Build ordered list — killers first
        List<int[]> orderedMoves = new ArrayList<>(Math.min(moveCount, 84));

        if (killer1Index >= 0) {
            orderedMoves.add(moves.get(killer1Index));
        }
        if (killer2Index >= 0 && killer2Index != killer1Index) {
            orderedMoves.add(moves.get(killer2Index));
        }

        // Step 3: Find opponent queens for heuristic
        int numOppQueens = 0;
        int[] oqR = new int[4];
        int[] oqC = new int[4];
        for (int r = 1; r <= 10; r++) {
            for (int c = 1; c <= 10; c++) {
                if (boardState[r][c] == oppColor && numOppQueens < 4) {
                    oqR[numOppQueens] = r;
                    oqC[numOppQueens] = c;
                    numOppQueens++;
                }
            }
        }

        // Step 4: Score remaining moves
        int nonKillerCount = moveCount
            - (killer1Index >= 0 ? 1 : 0)
            - (killer2Index >= 0 && killer2Index != killer1Index ? 1 : 0);
        int[] scoredFlat = new int[nonKillerCount * 2];
        int flatIdx = 0;

        for (int i = 0; i < moveCount; i++) {
            if (i == killer1Index || i == killer2Index) continue;

            int[] m = moves.get(i);
            int hScore = 0;

            // Queen destination centrality (integer math)
            int qCenterDist = Math.abs(2 * m[2] - 11) + Math.abs(2 * m[3] - 11);
            hScore += 20 - qCenterDist;

            // Arrow aggression: reward arrows near opponent queens
            for (int q = 0; q < numOppQueens; q++) {
                int dist = Math.max(Math.abs(m[4] - oqR[q]), Math.abs(m[5] - oqC[q]));
                if (dist <= 2) hScore += 15;
                if (dist == 1) hScore += 20;
            }

            scoredFlat[flatIdx] = i;
            scoredFlat[flatIdx + 1] = hScore;
            flatIdx += 2;
        }

        // Insertion sort
        int pairCount = flatIdx / 2;
        for (int i = 1; i < pairCount; i++) {
            int tmpIdx = scoredFlat[i * 2];
            int tmpScore = scoredFlat[i * 2 + 1];
            int j = i - 1;
            while (j >= 0 && scoredFlat[j * 2 + 1] < tmpScore) {
                scoredFlat[(j + 1) * 2] = scoredFlat[j * 2];
                scoredFlat[(j + 1) * 2 + 1] = scoredFlat[j * 2 + 1];
                j--;
            }
            scoredFlat[(j + 1) * 2] = tmpIdx;
            scoredFlat[(j + 1) * 2 + 1] = tmpScore;
        }

        // Step 5: Add top moves after killers
        int softCap = Math.min(80, pairCount);
        for (int i = 0; i < softCap; i++) {
            orderedMoves.add(moves.get(scoredFlat[i * 2]));
        }

        // =============================================
        // Alpha-beta search
        // =============================================
        if (maximizingPlayer) {
            int maxEval = Integer.MIN_VALUE;

            for (int[] move : orderedMoves) {
                if (isTimeUp()) break;

                int[][] newBoard = MoveGeneration.applyMove(boardState,
                        move[0], move[1], move[2], move[3], move[4], move[5]);

                int eval = minimaxAlphaBeta(newBoard, depth - 1,
                        alpha, beta, false, color);

                maxEval = Math.max(maxEval, eval);
                alpha = Math.max(alpha, eval);

                if (beta <= alpha) {
                    storeKillerMove(depthIndex, move);
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

                int eval = minimaxAlphaBeta(newBoard, depth - 1,
                        alpha, beta, true, color);

                minEval = Math.min(minEval, eval);
                beta = Math.min(beta, eval);

                if (beta <= alpha) {
                    storeKillerMove(depthIndex, move);
                    break;
                }
            }
            return minEval;
        }
    }

    private void storeKillerMove(int depthIndex, int[] move) {
        if (killerValid1[depthIndex] && movesMatch(move, killerMoves1[depthIndex])) {
            return;
        }
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

    private void updateGlobalAlpha(AtomicInteger globalAlpha, int score) {
        int currentVal;
        do {
            currentVal = globalAlpha.get();
            if (score <= currentVal) break;
        } while (!globalAlpha.compareAndSet(currentVal, score));
    }

    @Override public String userName() { return userName; }
    @Override public GameClient getGameClient() { return this.gameClient; }
    @Override public BaseGameGUI getGameGUI() { return this.gamegui; }
    @Override public void connect() { gameClient = new GameClient(userName, passwd, this); }
}