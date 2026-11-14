import { useEffect, useRef } from "react";
import { startKart } from "../../kart/kart";

/** Course de karts de Noël : plein écran par-dessus l'interface, retour au coach avec la croix. */
export function KartGame({ onExit }: { onExit: () => void }) {
  const host = useRef<HTMLDivElement>(null);
  const exit = useRef(onExit);
  exit.current = onExit;

  useEffect(() => {
    const el = host.current;
    if (!el) return;
    const game = startKart(el, { onExit: () => exit.current() });
    const prevOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      game.stop();
      document.body.style.overflow = prevOverflow;
    };
  }, []);

  return <div ref={host} className="game-host" role="application" aria-label="Le Grand Prix du Père Noël" />;
}
