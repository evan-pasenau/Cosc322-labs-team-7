package ubc.cosc322;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
    private Random random;

    private int moveCount = 0;
    
    private static final long MOVE_TIME_LIMIT_MS = 25000; // 30 second server limit
    private static final long SAFETY_MARGIN_MS = 3000;    // stop 3 seconds early for network latency
    private long moveStartTime;

    private ExecutorService executor;

    private class MoveResult {
        int[] move;
        int score;

        public MoveResult(int[] move, int score) {
            this.move = move;
            this.score = score;
        }
    }
    public static void main(String[] args) {
        COSC322Test player = new COSC322Test("player9", "name");

        if (player.getGameGUI() == null) {
            player.Go();
        } else {
            BaseGameGUI.sys_setup();
            java.awt.EventQueue.invokeLater(new Runnable() {
                public void run() {
                    player.Go();
                }
            });
        }
    }

    public COSC322Test(String userName, String passwd) {
        this.userName = userName;
        this.passwd = passwd;
        this.gamegui = new BaseGameGUI(this);
        this.random = new Random();

        int cores = Runtime.getRuntime().availableProcessors();
        this.executor = Executors.newFixedThreadPool(cores);
        System.out.println("Initialized thread pool with " + cores + " cores.");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (executor != null && !executor.isShutdown()) {
                System.out.println("Shutting down thread pool...");
                executor.shutdownNow();
            }
        }));
    }

    @Override
    public void onLogin() {
        userName = gameClient.getUserName();
        if (getGameGUI() != null) {
            getGameGUI().setRoomInformation(getGameClient().getRoomList());
        }
    }

    @Override
    public boolean handleGameMessage(String messageType, Map<String, Object> msgDetails) {

        if (getGameGUI() == null) {
            return true;
        }

        if (GameMessage.GAME_STATE_BOARD.equals(messageType)) {
            ArrayList<Integer> gameState =
                (ArrayList<Integer>) msgDetails.get(AmazonsGameMessage.GAME_STATE);
            getGameGUI().setGameState(gameState);
            board = MoveGeneration.parseGameState(gameState);

        } else if (GameMessage.GAME_ACTION_START.equals(messageType)) {
            moveStartTime = System.currentTimeMillis(); // start timer immediately

            String blackPlayer = (String) msgDetails.get(AmazonsGameMessage.PLAYER_BLACK);
            String whitePlayer = (String) msgDetails.get(AmazonsGameMessage.PLAYER_WHITE);
            isBlack = userName.equals(blackPlayer);

            System.out.println("\n========== GAME START ==========");
            System.out.println("White: " + whitePlayer + " | Black: " + blackPlayer);
            System.out.println("We are: " + (isBlack ? "Black" : "White"));
            System.out.println("================================");
            
            if(isBlack){
            makeIntelligentMove();
            }

        } else if (GameMessage.GAME_ACTION_MOVE.equals(messageType)) {
            getGameGUI().updateGameState(msgDetails);

            ArrayList<Integer> queenCurr = (ArrayList<Integer>) msgDetails.get(AmazonsGameMessage.QUEEN_POS_CURR);
            ArrayList<Integer> queenNext = (ArrayList<Integer>) msgDetails.get(AmazonsGameMessage.QUEEN_POS_NEXT);
            ArrayList<Integer> arrowPos = (ArrayList<Integer>) msgDetails.get(AmazonsGameMessage.ARROW_POS);

            // Check if this is our own move echoed back by looking at the piece BEFORE applying.
            // If the piece at the source position is our color, we already applied this move.
            int movedPiece = board[queenCurr.get(0)][queenCurr.get(1)];
            int ourColor = isBlack ? MoveGeneration.BLACK : MoveGeneration.WHITE;

            if (movedPiece == ourColor) {
                // Our own move echoed back — we already applied it, skip
                return true;
            }

            // Opponent's move — apply it and respond
            moveStartTime = System.currentTimeMillis(); // start timer immediately

            board = MoveGeneration.applyMove(board,
                queenCurr.get(0), queenCurr.get(1),
                queenNext.get(0), queenNext.get(1),
                arrowPos.get(0), arrowPos.get(1));

            moveCount++;
            makeIntelligentMove();
        }

        return true;
    }

    private static final int MAX_CANDIDATES = 50; // top moves to search deeply

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
        // Phase 1: Shallow eval to rank all moves
        // =============================================
        List<int[]> scoredMoves = new ArrayList<>(); // {qFromR, qFromC, qToR, qToC, arrowR, arrowC, score}
        int shallowCount = 0;

        System.out.print("Sorting moves (depth 0)... ");
        for (int i = 0; i < moves.size(); i++) {
            if (isTimeUp()) break;

            int[] move = moves.get(i);
            int[][] newBoard = MoveGeneration.applyMove(board,
                    move[0], move[1], move[2], move[3], move[4], move[5]);

            int myMoveCount = MoveGeneration.getAllMoves(newBoard, color).size();
            int score = evaluateBoard(newBoard, color, myMoveCount);

            scoredMoves.add(new int[]{move[0], move[1], move[2], move[3], move[4], move[5], score});
            shallowCount++;
        }

        // Sort by score descending (best moves first)
        scoredMoves.sort((a, b) -> Integer.compare(b[6], a[6]));

        // Best move so far is the top of the shallow sort
        int[] bestMove = scoredMoves.get(0);
        int bestScore = bestMove[6];
        int bestDepth = 0;

        long shallowTime = System.currentTimeMillis() - moveStartTime;
        System.out.println(shallowCount + "/" + totalMoves + " in " + shallowTime + "ms");

        // =============================================
        // Phase 2: Iterative deepening on top candidates
        // =============================================
        int numCandidates = Math.min(MAX_CANDIDATES, scoredMoves.size());

        // Extract just the move arrays for the top candidates
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

            // 2. Safely collect the results while watching the clock
            int completedCount = 0;
            for (Future<MoveResult> future : futures) {
                if (isTimeUp()) {
                    depthComplete = false;
                    break;
                }

                try {
                    // Wait for this specific thread to finish, checking the clock every 5ms
                    while (!future.isDone()) {
                        if (isTimeUp()) {
                            depthComplete = false;
                            break;
                        }
                        Thread.sleep(5); 
                    }

                    if (!depthComplete) break; // Break out of the collection loop if time ran out

                    MoveResult result = future.get(); // Safely grab the result
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

            System.out.println(); // newline after progress bar

            if (depthComplete && !isTimeUp()) {
                // Full depth completed — lock in these results
                bestMove = depthBestMove;
                bestScore = depthBestScore;
                bestDepth = depth;
                System.out.println("  -> Complete | Best score: " + bestScore);
            } else {
                // Partial depth — discard results and CANCEL remaining threads
                System.out.println("  -> Incomplete (timeout), discarding partial results");
                
                // CRITICAL: Stop the workers from calculating useless future depths
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

        // Validate the move before sending
        int piece = board[fromRow][fromCol];
        if (piece != color) {
            System.out.println("ERROR: Trying to move piece " + piece + " at (" + fromRow + "," + fromCol 
                + ") but we are color " + color + "! Board may be corrupted.");
        }
        if (board[toRow][toCol] != MoveGeneration.EMPTY) {
            System.out.println("ERROR: Destination (" + toRow + "," + toCol + ") is not empty, contains " 
                + board[toRow][toCol] + "!");
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


    /*
    * Evaluate the board state using mobility, territory, and freedom
    */
    private int evaluateBoard(int[][] boardState, int color, int numMyMoves) {
        int oppColor = (color == MoveGeneration.BLACK)
                ? MoveGeneration.WHITE : MoveGeneration.BLACK;

        // --- Mobility ---
        int myMoves = numMyMoves;
        int oppMoves = MoveGeneration.getAllMoves(boardState, oppColor).size();
        int mobilityScore = myMoves - oppMoves;

        // --- Territory ---
        int territoryScore = calculateVoronoiTerritory(boardState, color);

        // --- Freedom ---
        int myFreedom = calculateFreedom(boardState, color);
        int oppFreedom = calculateFreedom(boardState, oppColor);
        int freedomScore = myFreedom - oppFreedom;

        // --- Centralization ---
        int myCenter = calculateCentralization(boardState, color);
        int oppCenter = calculateCentralization(boardState, oppColor);
        int centralizationScore = myCenter - oppCenter;

        // --- Dynamic weights depending on game phase ---
        int territoryWeight = 3;
        int mobilityWeight = 2;
        int freedomWeight = 1;
        int centralWeight = 1;

        // Early game: emphasize centralization
        if (moveCount <= 6) {
            centralWeight = 4;
        }

        // Late game: emphasize territory
        if (moveCount >= 12) {
            territoryWeight = 5;
        }

        return (territoryWeight * territoryScore)
                + (mobilityWeight * mobilityScore)
                + (freedomWeight * freedomScore)
                + (centralWeight * centralizationScore);
    }

    /* 
     * Territory function, uses a Voronoi-like approach to count how many empty squares are closer
     * to our queens than opponent's queens, minus the opposite
     */
    private int calculateVoronoiTerritory(int[][] board, int color) {
        int oppColor = (color == MoveGeneration.BLACK)
                ? MoveGeneration.WHITE : MoveGeneration.BLACK;

        int[][] myDist = bfsQueenDistance(board, color);
        int[][] oppDist = bfsQueenDistance(board, oppColor);

        int myScore = 0;
        int oppScore = 0;

        for (int r = 1; r <= 10; r++) {
            for (int c = 1; c <= 10; c++) {
                if (board[r][c] != 0) continue;

                int md = myDist[r][c];
                int od = oppDist[r][c];

                if (md < od) myScore++;
                else if (od < md) oppScore++;
            }
        }

        return myScore - oppScore;
    }
    private int[][] bfsQueenDistance(int[][] board, int color) {
        int[][] dist = new int[11][11];
        for (int r = 1; r <= 10; r++) {
            for (int c = 1; c <= 10; c++) {
                dist[r][c] = Integer.MAX_VALUE;
            }
        }

        int[][] directions = {
            {-1,-1},{-1,0},{-1,1},
            {0,-1},{0,1},
            {1,-1},{1,0},{1,1}
        };

        // Seed BFS with all queen positions at distance 0
        java.util.LinkedList<int[]> queue = new java.util.LinkedList<>();
        for (int r = 1; r <= 10; r++) {
            for (int c = 1; c <= 10; c++) {
                if (board[r][c] == color) {
                    dist[r][c] = 0;
                    queue.add(new int[]{r, c});
                }
            }
        }

        // BFS: from each position, slide in all 8 directions (one queen move = one step in BFS)
        while (!queue.isEmpty()) {
            int[] pos = queue.poll();
            int r = pos[0], c = pos[1];
            int nextDist = dist[r][c] + 1;

            for (int[] d : directions) {
                int nr = r + d[0];
                int nc = c + d[1];

                // Slide along this direction
                while (nr >= 1 && nr <= 10 && nc >= 1 && nc <= 10
                        && board[nr][nc] == 0) {

                    if (nextDist < dist[nr][nc]) {
                        dist[nr][nc] = nextDist;
                        queue.add(new int[]{nr, nc});
                    }

                    nr += d[0];
                    nc += d[1];
                }
            }
        }

        return dist;
    }

    /*
     * Freedom function, counts how many empty squares are reachable from all of the player's 
     * queens, but counts each square multiple times if reachable from multiple queens
     */
    private int calculateFreedom(int[][] board, int color) {
        int freedom = 0;

        int[][] directions = {
            {-1,-1},{-1,0},{-1,1},
            {0,-1},{0,1},
            {1,-1},{1,0},{1,1}
        };

        for (int r = 1; r <= 10; r++) {
            for (int c = 1; c <= 10; c++) {

                if (board[r][c] == color) {

                    for (int[] d : directions) {
                        int nr = r + d[0];
                        int nc = c + d[1];

                        while (nr >= 1 && nr <= 10 && nc >= 1 && nc <= 10
                                && board[nr][nc] == 0) {

                            freedom++;
                            nr += d[0];
                            nc += d[1];
                        }
                    }
                }
            }
        }
        return freedom;
    }


    /*
     * Centralization function, rewards pieces that are closer to the center of the board
     */
    private int calculateCentralization(int[][] board, int color) {
        // Center of the 1-10 board is 5.5
        double center = 5.5;
        int score = 0;

        for (int r = 1; r <= 10; r++) {
            for (int c = 1; c <= 10; c++) {

                if (board[r][c] == color) {

                    double dist = Math.abs(r - center) + Math.abs(c - center);

                    // closer to center = higher score
                    score += (int)(12 - dist * 2);
                }
            }
        }
        return score;
    }


    /*
     * Minimax with alpha-beta pruning to optimize search
    */
    private int minimaxAlphaBeta(int[][] boardState, int depth, int alpha, int beta, boolean maximizingPlayer, int color) {
        // Bail out immediately if time is running out — don't waste time on eval
        if (isTimeUp()) {
            return 0; // neutral score, this result will be discarded by incomplete depth check
        }

        // Leaf node — run full static eval
        if (depth == 0) {
            int myMoves = MoveGeneration.getAllMoves(boardState, color).size();
            return evaluateBoard(boardState, color, myMoves);
        }

        int currentColor = maximizingPlayer ? color :
            (color == MoveGeneration.BLACK ? MoveGeneration.WHITE : MoveGeneration.BLACK);

        List<int[]> moves = MoveGeneration.getAllMoves(boardState, currentColor);

        // Terminal node — no moves means this player lost
        if (moves.isEmpty()) {
            // If the maximizing player has no moves, that's very bad; if minimizing, very good
            return maximizingPlayer ? Integer.MIN_VALUE + 1 : Integer.MAX_VALUE - 1;
        }

        // Randomize slightly to avoid deterministic ordering
        Collections.shuffle(moves, java.util.concurrent.ThreadLocalRandom.current());

        // Code to sort moves based on quick evaluation so we evaluate promising moves first,
        // takes a long time right now tho so commented out
        /*
        // Sort moves based on quick evaluation, ensures we evaluate promising moves first
        // and improves pruning
        final int[] count = {0};
        System.err.println("Sorting " + moves.size() + " moves at depth " + depth);
        Collections.sort(moves, (a, b) -> {
            int[][] boardA = MoveGeneration.applyMove(boardState,
                    a[0], a[1], a[2], a[3], a[4], a[5]);
            int[][] boardB = MoveGeneration.applyMove(boardState,
                    b[0], b[1], b[2], b[3], b[4], b[5]);

            int movesA = MoveGeneration.getAllMoves(boardA, color).size();
            int movesB = MoveGeneration.getAllMoves(boardB, color).size();

            System.err.println("Evaluating move " + count[0]++ + ": " + Arrays.toString(a) + " vs " + Arrays.toString(b));
            int scoreA = evaluateBoard(boardA, color, movesA);
            int scoreB = evaluateBoard(boardB, color, movesB);

            return Integer.compare(scoreB, scoreA);
        });
        System.err.println("Best move after sorting: " + Arrays.toString(moves.get(0)));
        */

        // Limit branching factor by searching 30 most promising moves (or fewer if less available)
            // or just 30 random moves if not sorting
        moves = moves.subList(0, Math.min(30, moves.size()));

        if (maximizingPlayer) {

            int maxEval = Integer.MIN_VALUE;

            for (int[] move : moves) {
                if (isTimeUp()) break;

                int[][] newBoard = MoveGeneration.applyMove(boardState,
                        move[0], move[1], move[2], move[3], move[4], move[5]);

                int eval = minimaxAlphaBeta(newBoard, depth - 1,
                        alpha, beta, false, color);

                maxEval = Math.max(maxEval, eval);
                alpha = Math.max(alpha, eval);

                if (beta <= alpha) {
                    break; // prune
                }
            }

            return maxEval;

        } else {

            int minEval = Integer.MAX_VALUE;

            for (int[] move : moves) {
                if (isTimeUp()) break;

                int[][] newBoard = MoveGeneration.applyMove(boardState,
                        move[0], move[1], move[2], move[3], move[4], move[5]);

                int eval = minimaxAlphaBeta(newBoard, depth - 1,
                        alpha, beta, true, color);

                minEval = Math.min(minEval, eval);
                beta = Math.min(beta, eval);

                if (beta <= alpha) {
                    break; // prune
                }
            }

            return minEval;
        }
    }

    private void updateGlobalAlpha(AtomicInteger globalAlpha, int score) {
        int currentVal;
        do {
            currentVal = globalAlpha.get();
            if (score <= currentVal) {
                break; // The new score isn't better, do nothing
            }
        } while (!globalAlpha.compareAndSet(currentVal, score)); // Only update if no other thread changed it first
    }

    @Override
    public String userName() {
        return userName;
    }

    @Override
    public GameClient getGameClient() {
        return this.gameClient;
    }

    @Override
    public BaseGameGUI getGameGUI() {
        return this.gamegui;
    }

    @Override
    public void connect() {
        gameClient = new GameClient(userName, passwd, this);
    }
}