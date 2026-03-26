package ubc.cosc322;

import java.util.ArrayList;
import java.util.List;

public class MoveGeneration {

    public static final int EMPTY = 0;
    public static final int WHITE = 2;
    public static final int BLACK = 1;
    public static final int ARROW = 3;

    private static final int[][] DIRECTIONS = {
        {-1, 0}, {1, 0}, {0, -1}, {0, 1},
        {-1, -1}, {-1, 1}, {1, -1}, {1, 1}
    };

    public static int[][] parseGameState(ArrayList<Integer> gameState) {
        int[][] board = new int[11][11];
        for (int row = 1; row <= 10; row++) {
            for (int col = 1; col <= 10; col++) {
                board[row][col] = gameState.get(row * 11 + col);
            }
        }
        return board;
    }

    public static int[][] applyMove(int[][] board, int fromRow, int fromCol,
                                     int toRow, int toCol, int arrowRow, int arrowCol) {
        int[][] newBoard = copyBoard(board);
        int piece = newBoard[fromRow][fromCol];
        newBoard[fromRow][fromCol] = EMPTY;
        newBoard[toRow][toCol] = piece;
        newBoard[arrowRow][arrowCol] = ARROW;
        return newBoard;
    }

    public static List<int[]> getQueenPositions(int[][] board, int color) {
        List<int[]> queens = new ArrayList<>();
        for (int row = 1; row <= 10; row++) {
            for (int col = 1; col <= 10; col++) {
                if (board[row][col] == color) {
                    queens.add(new int[]{row, col});
                }
            }
        }
        return queens;
    }

    public static List<int[]> getReachableSquares(int[][] board, int row, int col) {
        List<int[]> reachable = new ArrayList<>();
        for (int[] dir : DIRECTIONS) {
            int r = row + dir[0];
            int c = col + dir[1];
            while (r >= 1 && r <= 10 && c >= 1 && c <= 10 && board[r][c] == EMPTY) {
                reachable.add(new int[]{r, c});
                r += dir[0];
                c += dir[1];
            }
        }
        return reachable;
    }

    public static List<int[]> getAllMoves(int[][] board, int color) {
        List<int[]> allMoves = new ArrayList<>();

        for (int qRow = 1; qRow <= 10; qRow++) {
            for (int qCol = 1; qCol <= 10; qCol++) {
                if (board[qRow][qCol] != color) continue;

                List<int[]> queenMoves = getReachableSquares(board, qRow, qCol);

                for (int[] dest : queenMoves) {
                    int dRow = dest[0];
                    int dCol = dest[1];

                    board[qRow][qCol] = EMPTY;
                    board[dRow][dCol] = color;

                    List<int[]> arrowMoves = getReachableSquares(board, dRow, dCol);

                    for (int[] arrow : arrowMoves) {
                        allMoves.add(new int[]{qRow, qCol, dRow, dCol, arrow[0], arrow[1]});
                    }

                    board[dRow][dCol] = EMPTY;
                    board[qRow][qCol] = color;
                }
            }
        }

        return allMoves;
    }

    private static int[][] copyBoard(int[][] board) {
        int[][] copy = new int[11][11];
        for (int i = 1; i <= 10; i++) {
            System.arraycopy(board[i], 1, copy[i], 1, 10);
        }
        return copy;
    }
}