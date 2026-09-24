import sys
sys.path.insert(0, '.')
from common import write_test, tok

COMMON_GAME = """
Technical requirements (all mandatory):
A. Deliver ONE self-contained HTML file. Everything (HTML, CSS, JavaScript, graphics, sounds) must be inline. No external resources of any kind: no CDNs, libraries, web fonts, image or audio URLs, and no network requests. Draw graphics with <canvas> (or inline SVG) and generate any sounds with the Web Audio API.
B. Controls must work with the keyboard AND with the mouse, and with touch on phones.
C. Show the current score on screen at all times during play, and the best score of the session.
D. Show a start screen with the controls explained, and a game-over screen with the final score. The player must be able to restart from the game-over screen (by key and by click/tap) without reloading the page, and a restart must fully reset the game state.
E. Animate with requestAnimationFrame and make movement speed independent of the monitor's frame rate (use the elapsed time between frames).
F. The game must fit and scale to the browser window (desktop and phone), keeping the playfield fully visible.

Respond with a single ```html code block containing the complete file, and nothing else."""

GAME_RUBRIC = """Score the artifact out of 10 using these criteria. Reason about how the code will actually behave in a browser.

1. Runs and core loop works (0-3 points): 3 = loads without errors and the full loop works (start screen -> play -> game over -> restart); 2 = full loop works but with a noticeable bug; 1 = playable but the loop is incomplete (e.g. no working restart); 0 = does not start, throws an error that stops play, or is not a game.
2. Fidelity to the game-specific numbered requirements (0-3 points): start from 3 and subtract 1 for each numbered game requirement (1, 2, 3, ...) in the task that is missing or clearly broken, minimum 0.
3. Controls (0-1 point): 1 only if keyboard AND mouse/touch controls are both implemented as the task specifies; otherwise 0.
4. Game feel and difficulty (0-1 point): 1 if controls feel responsive and the difficulty is fair and increases sensibly; 0 if it is trivially easy, unfair, or unplayable.
5. Visual polish and UI clarity (0-1 point): 1 if the visuals are coherent and animated and the score/UI text is readable; 0 if it looks like placeholder boxes or the UI is unreadable.
6. Technical requirements A-F (0-1 point): 1 if all of A-F are met (self-contained, both input types, score and best score, start/game-over screens with clean restart, requestAnimationFrame with frame-rate-independent timing, scales to the window); 0 if any is missing.

Automatic caps: if the file loads ANY external resource (CDN, font, image or audio URL), the total may not exceed 3. If the artifact is not the requested game, the total is 0. Do not award points for features that are described in comments but not implemented."""

GAMES = [
 ('g01', 'hard', """Build "Ember Wing": a Flappy-Bird-style browser game in which the bird is ON FIRE.

Game requirements:
1. The player controls a small bird that is visibly on fire: animated flames or ember particles must stream from it at all times while playing.
2. Gravity pulls the bird down; flapping (Space, Arrow Up, mouse click or screen tap) gives it an upward boost.
3. Pairs of obstacles (pipes or chimneys) with a gap scroll in from the right at regular intervals, with the gap height varying randomly. Passing through a gap scores 1 point.
4. A HEAT meter is shown on screen. Heat rises steadily while flying. Water droplets float through some of the gaps; collecting one lowers the heat. If heat reaches 100%, the bird burns out and the game ends.
5. Touching an obstacle, the ground or the top of the screen ends the game.
6. The game gets gradually harder as the score rises (for example faster scrolling or smaller gaps), within fair limits.
""" + COMMON_GAME,
  "Checks: flame particles on the bird, gravity+flap, scrolling gaps with scoring, heat meter with cooling droplets and burn-out, collision rules, difficulty ramp, plus technical requirements A-F."),
 ('g02', 'hard', """Build "Spot Kick": a top-down penalty-shootout browser game against a goalkeeper AI.

Game requirements:
1. Show a top-down view of the penalty area: the goal (posts and net) at the top of the screen, a goalkeeper on the goal line, and the ball on the penalty spot.
2. Aiming and power: with the mouse or touch, the player aims by pointing/dragging and sets power by how long they hold (a visible power bar fills while holding) and shoots on release. With the keyboard, Arrow Left/Right aim, holding Space fills the power bar and releasing Space shoots. The current aim direction must be visible.
3. The ball travels along a visible path. Too much power makes the shot less accurate (for example random spread grows with power), and shots can miss wide of the posts.
4. The goalkeeper AI decides where to dive after a short reaction delay, reading the shot direction with imperfect accuracy, and it gets better after every goal the player scores. It can save shots by touching the ball.
5. A match is 5 penalties. Show "GOAL!", "SAVED!" or "MISSED!" after every kick, and a tally of goals and kicks taken (for example 3/5). After the fifth kick, show the final result.
6. The keeper must be beatable: a well-placed shot near a post with moderate power should usually score.
""" + COMMON_GAME,
  "Checks: top-down layout, mouse+keyboard aim/power mechanics, trajectory and accuracy trade-off, adaptive keeper AI, 5-kick match with outcome messages and tally, fairness, plus technical requirements A-F."),
 ('g03', 'hard', """Build "Portal Snake": a Snake browser game with teleporting portals.

Game requirements:
1. Classic grid-based Snake: the snake moves one cell per tick; Arrow keys and WASD steer it; on phones, swiping steers it (also offer on-screen arrow buttons for mouse users).
2. A 180-degree reversal must be ignored (pressing the opposite direction does nothing), and quick successive key presses must not be lost (queue at least two turns) or cause the snake to run into itself.
3. Eating food makes the snake grow by one cell and adds 1 point; the snake speeds up slightly every few points.
4. There are TWO pairs of portals on the board, each pair in its own colour. When the snake's head enters a portal cell, it exits from the other portal of the same pair, keeping its direction of travel, and the body follows through the portal.
5. Portals and food never spawn on the snake, on each other or on the border. All portals move to new random positions every time the snake has eaten 5 food items.
6. The border is a solid wall: hitting the wall or the snake's own body ends the game. Pressing P pauses and resumes the game.
""" + COMMON_GAME,
  "Checks: grid movement with keyboard, swipe and on-screen buttons, reversal guard and input queue, growth and speed-up, two colour-coded portal pairs with direction-preserving teleport, safe spawning and relocation every 5 foods, walls/self-collision and pause, plus technical requirements A-F."),
]

cases = [{'id': cid, 'prompt': p, 'notes': f"[{d}] {n} Scored by automated browser checks (50%) and the judge rubric (50%); also eligible for human Blind Review."} for cid, d, p, n in GAMES]
meta = dict(
    id='creative.one-shot-games', category='creative', name='Build a Game in One Shot',
    description='The model must ship a complete, polished browser game in a single HTML file with no external assets: a Flappy-style game with a burning bird and a heat mechanic, a top-down penalty shootout against an adaptive goalkeeper AI, and Snake with teleporting portals. Headless-browser checks catch crashes and dead input, and a judge rubric scores playability, fidelity and polish, so it separates models that can engineer a working interactive system from those that sketch one.',
    difficulty='hard', tags=['creative', 'html', 'canvas', 'game', 'artifact', 'one-shot'],
    hook='One prompt. One file. One playable game.',
    maxOutputTokens=32000,
    estimate={'inputTokens': int(sum(tok(c['prompt']) for c in cases) / len(cases)) + 20, 'outputTokens': 24000},
    scorer={'type': 'artifact', 'format': 'html', 'checks': [
        {'check': 'parses'}, {'check': 'no_external_requests'}, {'check': 'max_bytes', 'bytes': 300000},
        {'check': 'runs_without_errors'}, {'check': 'has_canvas_or_svg'}, {'check': 'responds_to_input'}],
        'rubric': GAME_RUBRIC, 'judgeWeight': 0.5},
)
print(write_test(meta, cases))

# ------------------------------------------------------------------------------------------ visual
SVG_COMMON = """
Output rules: respond with a single ```svg code block containing only the SVG document, and nothing else. The root element must be <svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="0 0 {w} {h}">. Use only inline SVG elements and attributes: no <image>, no external references or fonts, no <script>, no <foreignObject>, and no CSS animations. Keep the file under 40 kB."""

SVG_RUBRIC = """Score the SVG out of 10. Work out from the code exactly what will be drawn (positions, sizes, colours, counts and angles).

1. Numbered requirements (0-8 points): the task lists numbered requirements. Divide 8 points equally among them. A requirement earns its share only if it is fully and exactly satisfied as written (exact counts, positions, colours, angles or proportions; small anti-aliasing-level differences are fine). Partially met requirements earn half of their share. Round the total of this part to the nearest 0.5.
2. Overall quality (0-2 points): 2 = clean, well-composed and immediately recognisable; 1 = recognisable but crude or cluttered; 0 = hard to recognise.

Rounding: give the final score as an integer (round .5 up). Automatic zero: if the SVG would not render, depicts something other than what was asked, or uses <image>, <script> or external resources, the score is 0."""

VIS = [
 ('v01', 'medium', 512, 512, """Draw a lighthouse at night as a 512 x 512 SVG.

Numbered requirements:
1. The sky fills the whole background with a vertical gradient from very dark navy at the top to a lighter blue near the horizon, defined with a <linearGradient>.
2. A crescent moon (not a full circle) in the top-left quarter of the image (its whole shape within x < 256 and y < 256).
3. At least 6 small stars, all in the upper half of the image (y < 256).
4. A lighthouse standing roughly in the middle of the image, with a tower that is wider at the bottom than at the top, and exactly 3 red horizontal stripes on an otherwise white tower.
5. A lamp room with a glowing light at the top of the tower, and EXACTLY three visible light beams coming from it (semi-transparent yellow shapes spreading outwards).
6. Wavy water along the bottom of the image spanning the full width, drawn with at least two overlapping wave layers of different shades of blue that use curved <path> commands (Q, C, S or T).""",
  ['<linearGradient', '<path']),
 ('v02', 'hard', 400, 400, """Draw an analog wall clock showing exactly 10:08:30 as a 400 x 400 SVG.

Numbered requirements:
1. A circular clock face drawn with a <circle> element centred at (200, 200) with radius 180, white fill and a dark rim at least 6 units thick.
2. Exactly 60 tick marks around the edge, each drawn as a <line> element: the 12 hour ticks longer and thicker than the 48 minute ticks.
3. Numerals only at 12, 3, 6 and 9 (four <text> elements containing 12, 3, 6 and 9, each placed next to its hour position). No other numerals.
4. Each of the three hands is drawn as a <line> element starting at the centre. The hour hand points correctly for 10:08:30, i.e. at 304.25 degrees clockwise from 12 o'clock (10 x 30 + 8.5 x 0.5), and is the shortest and thickest hand.
5. The minute hand points at 51 degrees clockwise from 12 o'clock (8.5 minutes x 6) and is longer than the hour hand.
6. A thin red second hand points straight down at the 6 (180 degrees, i.e. 30 seconds) and is the longest hand.
7. A small filled circle covers the centre where the hands meet.""",
  ['<circle', '<line', '<text']),
 ('v03', 'medium', 600, 500, """Draw a bar chart of this data as a 600 x 500 SVG: Mon = 12, Tue = 7, Wed = 15, Thu = 4, Fri = 9.

Numbered requirements:
1. The baseline (x-axis) is a horizontal line at y = 450, and the y-axis is a vertical line at x = 60, both drawn in dark grey.
2. The scale is exactly 20 pixels per unit: each bar is a <rect> whose bottom edge sits on y = 450 and whose height is value x 20 (so Mon is 240 px tall and Thu is 80 px tall).
3. Five bars, each exactly 60 px wide, in the order Mon, Tue, Wed, Thu, Fri from left to right, with left edges at x = 90, 180, 270, 360 and 450.
4. The tallest bar is filled with #E4572E and the other four with #29335C.
5. Each bar has its day label (Mon, Tue, Wed, Thu, Fri) centred below the baseline and its value centred just above the top of the bar, all as <text> elements.
6. The y-axis has labelled tick marks at 0, 5, 10 and 15 at the correct heights (y = 450, 350, 250 and 150).""",
  ['<rect', '<text', '#E4572E', '#29335C']),
 ('v04', 'hard', 400, 400, """Draw a chess position as a 400 x 400 SVG.

Numbered requirements:
1. An 8 x 8 board filling the whole image (each square 50 x 50), with light squares #F0D9B5 and dark squares #B58863, drawn with <rect> elements.
2. Standard orientation: White plays up the board, so rank 1 is the bottom row, rank 8 the top row, file a is the left column and file h the right column. The square a1 (bottom-left) must be dark.
3. Each piece is a <text> element containing the Unicode chess symbol, roughly centred on its square: White king (♔) on e1, White rook (♖) on h1, White pawn (♙) on d4, Black king (♚) on e8, Black queen (♛) on d8 and Black pawn (♟) on f7.
4. No other pieces are on the board.
5. File letters a-h along the bottom edge and rank numbers 1-8 along the left edge, drawn as small <text> labels inside the edge squares.""",
  ['<rect', '<text', '♔', '♖', '♙', '♚', '♛', '♟', '#B58863']),
]

vcases = []
for cid, d, w, h, p, contains in VIS:
    vcases.append({'id': cid, 'prompt': p + "\n" + SVG_COMMON.format(w=w, h=h),
                   'scorer': {'type': 'artifact', 'format': 'svg', 'checks': [{'check': 'parses'}, {'check': 'max_bytes', 'bytes': 40000}] + [{'check': 'contains', 'text': t} for t in [f'viewBox="0 0 {w} {h}"'] + contains], 'rubric': SVG_RUBRIC, 'judgeWeight': 0.6},
                   'notes': f"[{d}] Automated checks: renders/parses, <= 40 kB, and contains viewBox=\"0 0 {w} {h}\", {', '.join(contains)} (each explicitly required by the prompt). The judge rubric splits 8 points across the numbered requirements and 2 for overall quality; judges can verify coordinates, counts and angles from the SVG source."})
meta = dict(
    id='visual.svg-illustration', category='visual', name='Precise SVG Illustration',
    description='Hand-written SVG illustrations with checkable requirements: a lighthouse scene with exact beam and stripe counts, a clock whose hands must sit at exact angles, a bar chart with exact pixel geometry and a chess position with exact piece placement. It tests spatial reasoning and precision in code-as-drawing, where approximate "looks about right" output loses points.',
    difficulty='medium', tags=['visual', 'svg', 'spatial', 'artifact', 'precision'],
    hook='Exactly three beams. Hands at exactly 304.25 degrees.',
    maxOutputTokens=16000,
    estimate={'inputTokens': int(sum(tok(c['prompt']) for c in vcases) / len(vcases)) + 20, 'outputTokens': 7000},
    scorer={'type': 'artifact', 'format': 'svg', 'checks': [{'check': 'parses'}, {'check': 'max_bytes', 'bytes': 40000}], 'rubric': SVG_RUBRIC, 'judgeWeight': 0.6},
)
print(write_test(meta, vcases))
