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

public class COSC322Test extends GamePlayer {

    private GameClient gameClient = null;
    private BaseGameGUI gamegui = null;

    private String userName = null;
    private String passwd = null;

    private boolean isBlack = false;
    private int[][] board;
    private Random random;

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

            makeRandomMove();
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