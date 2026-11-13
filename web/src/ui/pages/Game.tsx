import { useEffect, useRef } from "react";
import { startGame } from "../../game/game";

/** Mini-jeu de Noël : plein écran par-dessus l'interface, retour au coach avec la croix. */
export function Game({ onExit }: { onExit: () => void }) {
  const host = useRef<HTMLDivElement>(null);
  const exit = useRef(onExit);
  exit.current = onExit;

  useEffect(() => {
    const el = host.current;
    if (!el) return;
    const game = startGame(el, { onExit: () => exit.current() });
    const prevOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      game.stop();
      document.body.style.overflow = prevOverflow;
      if (document.fullscreenElement) void document.exitFullscreen().catch(() => {});
    };
  }, []);

  return <div ref={host} className="game-host" role="application" aria-label="La grande tournée du Père Noël" />;
}
