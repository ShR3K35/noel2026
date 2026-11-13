// Réglages du jeu. Les constantes de saut viennent du skill `platformer`
// (scripts/jump_tuner.py --height 4.2 --apex-time 0.4 --tile 48 --tick 60 --run-speed 7 --integrator half) :
// apex simulé 4,20 cases à 0,400 s, saut court 31 %, portée ~5 cases.

export const TILE = 48;
export const ROWS = 15;
export const TICK_RATE = 60;
export const DT = 1 / TICK_RATE;

export const RISE_GRAVITY = 2520;
export const FALL_GRAVITY = 4536; // 1,8 × la montée : chute franche, façon « Mario »
export const JUMP_VELOCITY = -1008;
export const JUMP_CUT = 0.45; // relâcher en montée coupe la vitesse : petit saut
export const APEX_THRESHOLD = 0.4 * 1008;
export const APEX_HANG = 0.5; // demi-gravité au sommet tant que le bouton est tenu
export const MAX_FALL = 20 * TILE; // 16 px par tick, moins d'une demi-case : pas de traversée

export const RUN_SPEED = 7 * TILE;
export const GROUND_ACCEL = RUN_SPEED / 0.09;
export const GROUND_DECEL = RUN_SPEED / 0.07;
export const AIR_ACCEL = GROUND_ACCEL * 0.65;

export const COYOTE_TICKS = Math.round(0.1 * TICK_RATE); // 6
export const BUFFER_TICKS = Math.round(0.12 * TICK_RATE); // 7
export const CORNER_CORRECTION = 14; // px glissés pour ne pas buter sur un coin de plafond

export const STOMP_BOUNCE = -640;
export const STOMP_BOUNCE_HELD = -930;
export const HURT_INVULN_TICKS = 80;
export const MAX_HEARTS = 3;

export const PLAYER_W = 30;
export const PLAYER_H = 56;
