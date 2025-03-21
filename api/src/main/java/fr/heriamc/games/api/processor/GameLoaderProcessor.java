package fr.heriamc.games.api.processor;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import fr.heriamc.api.game.GameState;
import fr.heriamc.api.game.packet.GameCreatedPacket;
import fr.heriamc.api.game.packet.GameCreationResult;
import fr.heriamc.bukkit.HeriaBukkit;
import fr.heriamc.games.api.pool.GameManager;
import fr.heriamc.games.api.pool.GamePool;
import fr.heriamc.games.engine.MiniGame;
import fr.heriamc.games.engine.event.game.GameLoadedEvent;
import fr.heriamc.games.engine.utils.Pair;
import fr.heriamc.games.engine.utils.Utils;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.Bukkit;

import java.util.UUID;
import java.util.concurrent.*;

@Getter
@Slf4j
public class GameLoaderProcessor<M extends MiniGame> implements GameProcessor<M> {

    private final GamePool<M> gamePool;
    private final GameManager<M> gameManager;

    private final ScheduledExecutorService executorService;
    private final BlockingQueue<CompletableFuture<Pair<UUID, M>>> queue;

    public GameLoaderProcessor(GamePool<M> gamePool) {
        this.gamePool = gamePool;
        this.gameManager = gamePool.getGamesManager();
        this.executorService = Executors.newSingleThreadScheduledExecutor(new ThreadFactoryBuilder().setNameFormat("game-queue-processor-%d").build());
        this.queue = new LinkedBlockingQueue<>(gamePool.getMaxPoolSize());
        this.executorService.scheduleAtFixedRate(this::process, 20, 20, TimeUnit.MILLISECONDS);
    }

    @Override
    public void process() {
        var future = queue.peek();

        if (future != null && future.isDone()) {
            try {
                if (queue.isEmpty()) return;

                var pair = queue.peek().get();
                var id = pair.getKey();
                var game = pair.getValue();

                if (game.getState() == GameState.LOADING) {
                    log.info("[GameProcessor] START LOADING {}", game.getFullName());
                    game.load();
                    gamePool.getGamePoolHeartBeat().forceUpdate();
                    game.setState(GameState.LOADING_IN_PROGRESS);
                }

                if (game.getState() == GameState.WAIT || game.getState() == GameState.ALWAYS_PLAYING) {
                    log.info("[GameProcessor] GAME : {} completely loaded", game.getFullName());
                    gamePool.getGamePoolHeartBeat().forceUpdate();

                    Bukkit.getPluginManager().callEvent(new GameLoadedEvent<>(game, gamePool));

                    if (id != null && gamePool.getGameCreationCache().containsKey(id))
                        gamePool.sendGameCreatedPacket(new GameCreatedPacket(
                                id, game.getFullName(),
                                HeriaBukkit.get().getInstanceName(), GameCreationResult.SUCCESS));

                    queue.poll();
                    log.info("[GameProcessor] QUEUE: ELEMENTS LEFT={}", queue.size());
                }

            } catch (InterruptedException | ExecutionException exception) {
                log.error("GAME LOADER PROCESSOR EXCEPTION", exception);
            }
        }
    }

    @Override
    public void addGame(UUID uuid, M game) {
        queue.add(CompletableFuture.supplyAsync(() -> new Pair<>(uuid, game)));
    }

    @Override
    public void shutdown() {
        queue.clear();
        Utils.awaitTerminationAfterShutdown(executorService, 1, TimeUnit.SECONDS);
    }

}