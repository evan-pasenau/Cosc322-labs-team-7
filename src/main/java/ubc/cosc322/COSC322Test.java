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

public class COSC322Test extends GamePlayer {

    private GameClient gameClient = null;
    private BaseGameGUI gamegui = null;

    private String userName = null;
    private String passwd = null;

    private boolean isBlack = false;
    private int[][] board;
    private Random random;

    private int moveCount = 0;

    public static void main(String[] args) {
        COSC322Test player = new COSC322Test("player2", "name");

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
        System.out.println("Message type: " + messageType);

        if (getGameGUI() == null) {
            return true;
        }

        if (GameMessage.GAME_STATE_BOARD.equals(messageType)) {
            ArrayList<Integer> gameState =
                (ArrayList<Integer>) msgDetails.get(AmazonsGameMessage.GAME_STATE);
            getGameGUI().setGameState(gameState);
            board = MoveGeneration.parseGameState(gameState);

        } else if (GameMessage.GAME_ACTION_START.equals(messageType)) {
            String blackPlayer = (String) msgDetails.get(AmazonsGameMessage.PLAYER_BLACK);
            isBlack = userName.equals(blackPlayer);

            if (!isBlack) {
                makeRandomMove();
            }

        } else if (GameMessage.GAME_ACTION_MOVE.equals(messageType)) {
            getGameGUI().updateGameState(msgDetails);

            ArrayList<Integer> queenCurr = (ArrayList<Integer>) msgDetails.get(AmazonsGameMessage.QUEEN_POS_CURR);
            ArrayList<Integer> queenNext = (ArrayList<Integer>) msgDetails.get(AmazonsGameMessage.QUEEN_POS_NEXT);
            ArrayList<Integer> arrowPos = (ArrayList<Integer>) msgDetails.get(AmazonsGameMessage.ARROW_POS);

            board = MoveGeneration.applyMove(board,
                queenCurr.get(0), queenCurr.get(1),
                queenNext.get(0), queenNext.get(1),
                arrowPos.get(0), arrowPos.get(1));

            //moveCount++;

            if(userName.equals("player2"))   
                makeRandomMove();
            else if(userName.equals("player1"))
                makeIntelligentMove();
        }

        return true;
    }

    private void makeRandomMove() {
        int color = isBlack ? MoveGeneration.BLACK : MoveGeneration.WHITE;
        List<int[]> moves = MoveGeneration.getAllMoves(board, color);

        if (moves.isEmpty()) {
            System.out.println("No moves available — we lost!");
            return;
        }

        System.out.println("Total legal moves: " + moves.size());

        int[] move = moves.get(random.nextInt(moves.size()));
        int fromRow = move[0], fromCol = move[1];
        int toRow = move[2], toCol = move[3];
        int arrowRow = move[4], arrowCol = move[5];

        ArrayList<Integer> queenPosCurrent = new ArrayList<>(Arrays.asList(fromRow, fromCol));
        ArrayList<Integer> queenPosNew = new ArrayList<>(Arrays.asList(toRow, toCol));
        ArrayList<Integer> arrowPos = new ArrayList<>(Arrays.asList(arrowRow, arrowCol));

        getGameClient().sendMoveMessage(queenPosCurrent, queenPosNew, arrowPos);
        getGameGUI().updateGameState(queenPosCurrent, queenPosNew, arrowPos);

        board = MoveGeneration.applyMove(board, fromRow, fromCol, toRow, toCol, arrowRow, arrowCol);
        //moveCount++;
    }

    private void makeIntelligentMove() {
        int color = isBlack ? MoveGeneration.BLACK : MoveGeneration.WHITE;
        List<int[]> moves = MoveGeneration.getAllMoves(board, color);

        if (moves.isEmpty()) {
            System.out.println("No moves available — we lost!");
            return;
        }

        int bestScore = Integer.MIN_VALUE;
        int[] bestMove = moves.get(0);

        int totalMoves = moves.size();
        System.out.println("Total legal moves: " + totalMoves);

        // --- Dynamic depth and sample size selection ---
        int depth;
        int sampleSize;
        if (totalMoves > 1000) {
            depth = 2;        // very early game
            sampleSize = 50;
        } 
        else if (totalMoves > 500) {
            depth = 3;        // early-mid game
            sampleSize = 40;
        } 
        else if (totalMoves > 250) {
            depth = 3;        // mid game
            sampleSize = 70;
        } 
        else if (totalMoves > 100) {
            depth = 3;        // late mid game
            sampleSize = 80;
        } 
        else {
            depth = 6;        // endgame
            sampleSize = moves.size(); // search all moves when few are left
        }

        System.out.println("Search depth: " + depth);

        // Shuffle moves to randomize selection
        List<int[]> shuffledMoves = new ArrayList<>(moves);
        java.util.Collections.shuffle(shuffledMoves, random);

        // Limit sample of moves searched with minimax as evaluating all takes far too long in the early game
        // When less moves are available we can afford to search more of them
            // This is a simple way to reduce the branching factor for deeper search, taking too long at the beginning
        for (int i = 0; i < sampleSize; i++) {
            int[] move = shuffledMoves.get(i);
            int[][] newBoard = MoveGeneration.applyMove(board,
                    move[0], move[1], move[2], move[3], move[4], move[5]);

            //System.err.println("Evaluating move: " + Arrays.toString(move));
            int score = minimaxAlphaBeta(newBoard, depth, Integer.MIN_VALUE,
                Integer.MAX_VALUE, false, color);

            //System.err.println("[" + i + "] Move score: " + score);
            if (score > bestScore) {
                bestScore = score;
                bestMove = move;
            }
        }

        int fromRow = bestMove[0], fromCol = bestMove[1];
        int toRow = bestMove[2], toCol = bestMove[3];
        int arrowRow = bestMove[4], arrowCol = bestMove[5];

        ArrayList<Integer> queenPosCurrent = new ArrayList<>(Arrays.asList(fromRow, fromCol));
        ArrayList<Integer> queenPosNew = new ArrayList<>(Arrays.asList(toRow, toCol));
        ArrayList<Integer> arrowPos = new ArrayList<>(Arrays.asList(arrowRow, arrowCol));

        getGameClient().sendMoveMessage(queenPosCurrent, queenPosNew, arrowPos);
        getGameGUI().updateGameState(queenPosCurrent, queenPosNew, arrowPos);

        System.out.println("Making move: " + moveCount);
        board = MoveGeneration.applyMove(board, fromRow, fromCol, toRow, toCol, arrowRow, arrowCol);
        System.out.println("Best move score: " + bestScore);
        moveCount++;
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
        int size = board.length;
        int myScore = 0;
        int oppScore = 0;

        for (int r = 0; r < size; r++) {
            for (int c = 0; c < size; c++) {

                if (board[r][c] != 0)
                    continue;

                int myDist = queenDistance(board, r, c, color);
                int oppDist = queenDistance(board, r, c, oppColor);

                if (myDist < oppDist)
                    myScore++;
                else if (oppDist < myDist)
                    oppScore++;
            }
        }

        return myScore - oppScore;
    }


    /*
     * Calculates the minimum distance from a target square to any queen of the specified color
     * Used in the Voronoi territory calculation to determine which player controls each empty square
     */
    private int queenDistance(int[][] board, int targetR, int targetC, int color) {
        int size = board.length;
        int minDist = Integer.MAX_VALUE;

        int[][] directions = {
            {-1,-1},{-1,0},{-1,1},
            {0,-1},{0,1},
            {1,-1},{1,0},{1,1}
        };

        for (int r = 0; r < size; r++) {
            for (int c = 0; c < size; c++) {

                if (board[r][c] == color) {

                    int dist = Math.max(Math.abs(targetR - r), Math.abs(targetC - c));

                    if (dist < minDist)
                        minDist = dist;
                }
            }
        }
        return minDist;
    }

    /*
     * Freedom function, counts how many empty squares are reachable from all of the player's 
     * queens, but counts each square multiple times if reachable from multiple queens
     */
    private int calculateFreedom(int[][] board, int color) {
        int size = board.length;
        int freedom = 0;

        int[][] directions = {
            {-1,-1},{-1,0},{-1,1},
            {0,-1},{0,1},
            {1,-1},{1,0},{1,1}
        };

        for (int r = 0; r < size; r++) {
            for (int c = 0; c < size; c++) {

                if (board[r][c] == color) {

                    for (int[] d : directions) {
                        int nr = r + d[0];
                        int nc = c + d[1];

                        while (nr >= 0 && nr < size && nc >= 0 && nc < size
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
        int size = board.length;
        double center = (size - 1) / 2.0;
        int score = 0;

        for (int r = 0; r < size; r++) {
            for (int c = 0; c < size; c++) {

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
        int currentColor = maximizingPlayer ? color :
            (color == MoveGeneration.BLACK ? MoveGeneration.WHITE : MoveGeneration.BLACK);

        List<int[]> moves = MoveGeneration.getAllMoves(boardState, currentColor);
        // Randomize slightly to avoid deterministic ordering
        Collections.shuffle(moves, random);

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

        // Leaf node
        if (depth == 0 || moves.isEmpty()) {
            int myMoves = MoveGeneration.getAllMoves(boardState, color).size();
            return evaluateBoard(boardState, color, myMoves);
        }

        if (maximizingPlayer) {

            int maxEval = Integer.MIN_VALUE;

            for (int[] move : moves) {

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