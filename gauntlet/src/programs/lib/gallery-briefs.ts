/**
 * The Gallery Masterpiece: eight commission briefs, written like a museum commissioning a painting.
 *
 * Every brief names the subject and composition, 6 checkable required elements, the medium and the
 * art-historical manner, the palette and light, the mood, the artist's explicit freedoms, and what must NOT be
 * in the picture. The same briefs are sent, word for word, to every model (image models and, in "Painted in
 * Code", text models writing SVG). This file is part of both programs' source hash: editing a brief changes the
 * tests' hashes, so bump their versions.
 */

export interface BriefItem {
  /** "E1".."E6" for required elements, "N1".. for things to avoid. */
  id: string;
  /** What the judge checks, one plain sentence. */
  text: string;
}

export interface Brief {
  /** 1-based; the test seed selects the brief. */
  n: number;
  id: string;
  title: string;
  medium: string;
  /** The art-historical manner, with reference artists. */
  style: string;
  /** Subject and composition, very specifically. */
  subject: string;
  elements: BriefItem[];
  palette: string;
  mood: string;
  /** What the artist is free to decide. */
  freedom: string;
  avoid: BriefItem[];
}

const NO_TEXT = 'No text of any kind: no letters, words, numbers, signatures, seals, stamps or watermarks anywhere in the picture.';
const NO_FRAME = 'No border, frame, mat or gallery wall drawn in: the painting fills the whole canvas edge to edge.';

export const BRIEFS: Brief[] = [
  {
    n: 1,
    id: 'keepers-daughter',
    title: 'The Keeper’s Daughter',
    medium: 'Oil on canvas',
    style: 'Dutch Golden Age interior, in the manner of Johannes Vermeer and Pieter de Hooch',
    subject:
      'The lighthouse keeper’s daughter sits reading by a tall leaded window at dusk. The window is on the left of the picture, and through it the lighthouse stands on a rocky point in the left third of the composition, its lamp just lit. A grey cat sleeps curled on the deep window sill. On a table beside her, a brass oil lamp burns, and a blue-and-white jug stands next to it.',
    elements: [
      { id: 'E1', text: 'A young woman seated, reading an open book held in her hands' },
      { id: 'E2', text: 'A window on the left side of the picture' },
      { id: 'E3', text: 'A lighthouse visible through the window, in the left third of the picture, with its light lit' },
      { id: 'E4', text: 'A grey cat asleep on the window sill' },
      { id: 'E5', text: 'A lit brass oil lamp on a table' },
      { id: 'E6', text: 'A blue-and-white jug on the table' },
    ],
    palette: 'Warm chiaroscuro: amber lamplight on her face and the book against the cool blue of the dusk outside; deep umber shadows.',
    mood: 'Quiet, absorbed, tender: a private moment of reading.',
    freedom: 'You choose her pose, her clothes, the season outside, the room’s other furnishings and exactly where the viewer stands, within these rules.',
    avoid: [
      { id: 'N1', text: NO_TEXT },
      { id: 'N2', text: NO_FRAME },
      { id: 'N3', text: 'Nothing modern: no electric lights, glasses of modern design, phones, plastic or modern clothing.' },
    ],
  },
  {
    n: 2,
    id: 'harbour-first-light',
    title: 'Harbour at First Light',
    medium: 'Oil on canvas',
    style: 'French Impressionist plein-air painting, in the manner of Claude Monet and Berthe Morisot',
    subject:
      'A small fishing harbour at sunrise, seen from the end of a stone quay. Three wooden sailing boats with furled sails ride at anchor in the middle distance, their reflections broken into dabs of colour on the water. Houses with red-tiled roofs line the far quay. A figure in a straw hat stands on the near quay watching the boats, and gulls wheel above the water. The sun sits low on the horizon.',
    elements: [
      { id: 'E1', text: 'Exactly three sailing boats with their sails furled (not raised)' },
      { id: 'E2', text: 'The low sun on or just above the horizon' },
      { id: 'E3', text: 'Reflections in the water painted as broken strokes or dabs of colour' },
      { id: 'E4', text: 'A row of houses with red or terracotta roofs along a quay' },
      { id: 'E5', text: 'A standing figure wearing a straw hat on the near quay' },
      { id: 'E6', text: 'Gulls in the sky' },
    ],
    palette: 'Pastel dawn: rose, lilac, pale gold and sea-green; no black, shadows mixed from colour.',
    mood: 'Fresh, hopeful, the hush of early morning.',
    freedom: 'You choose the weather within a clear sunrise, the boats’ colours, the figure’s pose and how loose the brushwork is, within these rules.',
    avoid: [
      { id: 'N1', text: NO_TEXT },
      { id: 'N2', text: NO_FRAME },
      { id: 'N3', text: 'Nothing modern: no motorboats, cars, power lines or modern buildings.' },
    ],
  },
  {
    n: 3,
    id: 'storm-over-the-pass',
    title: 'The Storm Over the Pass',
    medium: 'Oil on canvas',
    style: 'Romantic sublime landscape, in the manner of Caspar David Friedrich and J. M. W. Turner',
    subject:
      'A lone traveller, seen from behind, stands on a rocky outcrop in the foreground and looks out over a vast mountain valley. A waterfall plunges down the valley wall. Storm clouds are breaking, and a single shaft of sunlight strikes a distant snow-capped peak. On a far ridge stands a ruined gothic chapel. A wind-bent pine clings to the rocks beside the traveller.',
    elements: [
      { id: 'E1', text: 'A single human figure seen from behind, standing on rocks in the foreground' },
      { id: 'E2', text: 'A deep mountain valley below the figure' },
      { id: 'E3', text: 'A waterfall' },
      { id: 'E4', text: 'Storm clouds with one distinct shaft of sunlight falling on a distant snow-capped peak' },
      { id: 'E5', text: 'A ruined gothic chapel on a distant ridge' },
      { id: 'E6', text: 'A wind-bent pine tree near the figure' },
    ],
    palette: 'Slate greys, cold blues and storm violet, broken by one burst of warm gold light.',
    mood: 'Awe and solitude before the power of nature: the sublime.',
    freedom: 'You choose the traveller’s clothing and staff, the time of year and the exact shape of the mountains, within these rules.',
    avoid: [
      { id: 'N1', text: NO_TEXT },
      { id: 'N2', text: NO_FRAME },
      { id: 'N3', text: 'Nothing modern: no roads, cable cars, modern hiking gear or buildings other than the ruined chapel.' },
    ],
  },
  {
    n: 4,
    id: 'moonlit-crossing',
    title: 'Moonlit Crossing in the Rain',
    medium: 'Colour woodblock print',
    style: 'Japanese ukiyo-e landscape print, in the manner of Utagawa Hiroshige and Katsushika Hokusai',
    subject:
      'A wooden arched bridge crosses a wide river at night in the rain. Two travellers in straw hats, one holding an oiled-paper umbrella, hurry across the bridge. Below, a boatman poles a small flat boat along the river. A full moon shows through thin clouds, and a snow-capped conical mountain rises in the far distance. Slanting lines of rain cross the whole scene, and a willow hangs over the near bank.',
    elements: [
      { id: 'E1', text: 'A wooden arched bridge over a river' },
      { id: 'E2', text: 'Two travellers on the bridge, at least one with an oiled-paper umbrella' },
      { id: 'E3', text: 'A boatman poling a small boat on the river' },
      { id: 'E4', text: 'A full moon, partly veiled by cloud' },
      { id: 'E5', text: 'A snow-capped conical mountain in the far distance' },
      { id: 'E6', text: 'Rain drawn as fine slanting lines across the scene' },
    ],
    palette: 'Indigo and Prussian blue night, pale moon ivory, touches of vermilion; flat areas of colour with woodblock gradation (bokashi) in the sky.',
    mood: 'Hushed and poetic: travellers caught in the rain on a moonlit night.',
    freedom: 'You choose the travellers’ clothing, the bridge’s angle and the placement of the willow, within these rules.',
    avoid: [
      { id: 'N1', text: 'No text of any kind: no title cartouche, publisher’s seal, signature, letters, numbers or watermarks (even though real prints have them).' },
      { id: 'N2', text: NO_FRAME },
      { id: 'N3', text: 'No photographic or 3D-rendered look: it must read as a flat woodblock print with clear outlines.' },
    ],
  },
  {
    n: 5,
    id: 'peacock-muse',
    title: 'The Muse of the Peacock Garden',
    medium: 'Colour lithograph (decorative panel)',
    style: 'Art Nouveau decorative panel, in the manner of Alphonse Mucha',
    subject:
      'A young woman stands in profile in a summer garden, her long hair flowing in sweeping whiplash curves. Behind her head is a large circular halo-like disc of ornament. She holds a lyre against her shoulder. Irises and lilies grow around her, and a peacock with its tail spread stands at her feet. Her gown falls in long, flowing folds.',
    elements: [
      { id: 'E1', text: 'A young woman shown in profile (side view of her face)' },
      { id: 'E2', text: 'Long flowing hair drawn in sweeping, curling lines' },
      { id: 'E3', text: 'A large decorative circular disc behind her head' },
      { id: 'E4', text: 'She holds a lyre' },
      { id: 'E5', text: 'Irises or lilies around her' },
      { id: 'E6', text: 'A peacock with its tail fanned out, at her feet' },
    ],
    palette: 'Soft muted jewel tones: sage green, dusty rose, ochre and peacock teal, with contour lines and flat decorative fields.',
    mood: 'Serene, graceful, dreamlike.',
    freedom: 'You choose her gown, the direction she faces, the pattern inside the disc and the arrangement of the flowers. Decorative motifs may fill the composition, within these rules.',
    avoid: [
      { id: 'N1', text: NO_TEXT },
      { id: 'N2', text: 'No border, frame, mat or panel edging drawn around the picture: the ornament stays inside the composition and the image fills the canvas edge to edge.' },
      { id: 'N3', text: 'Nothing modern: no modern clothing, jewellery, objects or photographic look.' },
    ],
  },
  {
    n: 6,
    id: 'harvest-loggia',
    title: 'The Harvest in the Loggia',
    medium: 'Fresco',
    style: 'Italian Early Renaissance fresco, in the manner of Piero della Francesca and Fra Angelico',
    subject:
      'An open loggia with three round arches on slender columns. Under the arches, a woman in a blue mantle offers a basket of bread and grapes to an elderly bearded man. A child kneels beside them holding a white lamb. Through the arches, gentle Tuscan hills with cypress trees roll away into the distance. The floor is laid in tiles drawn in clear one-point perspective.',
    elements: [
      { id: 'E1', text: 'A loggia with exactly three round arches' },
      { id: 'E2', text: 'A woman in a blue mantle offering a basket' },
      { id: 'E3', text: 'Bread and grapes in the basket' },
      { id: 'E4', text: 'An elderly bearded man receiving the offering' },
      { id: 'E5', text: 'A kneeling child holding a white lamb' },
      { id: 'E6', text: 'Hills with cypress trees seen through the arches' },
    ],
    palette: 'Pale, chalky fresco colours: lapis blue, terracotta, soft ochre and sage, with even daylight and gentle modelling.',
    mood: 'Calm, dignified, timeless.',
    freedom: 'You choose the figures’ poses and gestures, the architecture’s decoration and the season in the hills, within these rules.',
    avoid: [
      { id: 'N1', text: NO_TEXT },
      { id: 'N2', text: 'No border, painted frame or plaster edge drawn around the picture: the fresco fills the canvas edge to edge.' },
      { id: 'N3', text: 'Nothing modern and no halos or other religious symbols: it is a scene of everyday life.' },
    ],
  },
  {
    n: 7,
    id: 'herb-gatherer',
    title: 'The Herb Gatherer',
    medium: 'Oil on canvas',
    style: 'Pre-Raphaelite painting, in the manner of John William Waterhouse and John Everett Millais',
    subject:
      'In a spring woodland, a young woman with long auburn hair, in a green velvet medieval gown, kneels at the edge of a clear brook, gathering foxgloves into a wicker basket. A white hare watches her from the ferns. Bluebells carpet the ground between the trees. Far off, through a gap in the trees, the tower of a stone castle can be seen.',
    elements: [
      { id: 'E1', text: 'A young woman with long auburn (red-brown) hair' },
      { id: 'E2', text: 'A green medieval-style gown' },
      { id: 'E3', text: 'She kneels at the edge of a brook or stream' },
      { id: 'E4', text: 'Foxgloves (tall spires of bell-shaped flowers) in a wicker basket' },
      { id: 'E5', text: 'A white hare among ferns' },
      { id: 'E6', text: 'A distant castle tower seen through the trees' },
    ],
    palette: 'Jewel-bright and precise: emerald, violet-blue, auburn and ivory, every leaf and petal crisply painted in clear spring light.',
    mood: 'Enchanted, gentle, a little melancholy.',
    freedom: 'You choose her pose and expression, the brook’s course and how the light falls through the canopy, within these rules.',
    avoid: [
      { id: 'N1', text: NO_TEXT },
      { id: 'N2', text: NO_FRAME },
      { id: 'N3', text: 'Nothing modern: no modern clothing, tools, fences or buildings.' },
    ],
  },
  {
    n: 8,
    id: 'evening-on-the-river',
    title: 'Evening on the River',
    medium: 'Oil on canvas',
    style: 'Hudson River School luminism, in the manner of Thomas Cole, Frederic Edwin Church and Albert Bierstadt',
    subject:
      'A wide river valley at golden hour. A white birch tree stands in the left foreground, framing the view. On the right bank, a small log cabin sends a thin line of smoke into the still air. Two figures paddle a canoe across the calm, mirror-like river. A deer drinks at the near shore. Blue mountains fade into the distance under a sky of glowing cumulus clouds.',
    elements: [
      { id: 'E1', text: 'A white birch tree in the left foreground' },
      { id: 'E2', text: 'A log cabin on the right bank with smoke rising from it' },
      { id: 'E3', text: 'A canoe with two people in it on the river' },
      { id: 'E4', text: 'A deer drinking at the water’s edge' },
      { id: 'E5', text: 'Distant blue mountains' },
      { id: 'E6', text: 'Glowing, sunlit cumulus clouds in the sky' },
    ],
    palette: 'Luminous golden hour: honey gold, peach and soft rose light over cool blue-green shadows; a calm, mirror-like river.',
    mood: 'Peaceful, reverent, glowing: the calm at the end of the day.',
    freedom: 'You choose the season’s foliage, the exact bend of the river and the time within golden hour, within these rules.',
    avoid: [
      { id: 'N1', text: NO_TEXT },
      { id: 'N2', text: NO_FRAME },
      { id: 'N3', text: 'Nothing modern: no roads, bridges, power lines, motorboats or modern buildings.' },
    ],
  },
];

export function briefForSeed(seed: number): Brief {
  const b = BRIEFS.find((x) => x.n === seed);
  if (!b) throw new Error(`No Gallery brief number ${seed}: use seeds 1–${BRIEFS.length}`);
  return b;
}

/** Every checklist line the judges answer: the required elements, then the things to avoid. */
export function briefItems(b: Brief): Array<BriefItem & { kind: 'element' | 'avoid' }> {
  return [...b.elements.map((e) => ({ ...e, kind: 'element' as const })), ...b.avoid.map((a) => ({ ...a, kind: 'avoid' as const }))];
}

/** The commission, as every contestant and every judge reads it. */
export function briefText(b: Brief): string {
  return [
    `COMMISSION No. ${b.n}: “${b.title}”`,
    '',
    `Medium and style: ${b.medium}, ${b.style}.`,
    '',
    `Subject and composition: ${b.subject}`,
    '',
    'Required elements (each must be clearly visible):',
    ...b.elements.map((e, i) => `${i + 1}. ${e.text}.`),
    '',
    `Palette and light: ${b.palette}`,
    `Mood: ${b.mood}`,
    '',
    `Your artistic freedom: ${b.freedom} Artistry is rewarded as much as following the brief.`,
    '',
    'Do not include:',
    ...b.avoid.map((a) => `- ${a.text}`),
  ].join('\n');
}

/** The prompt an image model receives (The Gallery Masterpiece). */
export function imagePrompt(b: Brief): string {
  return [
    'The museum commissions an original painting from you for its gallery. Paint it: create the image itself.',
    '',
    briefText(b),
    '',
    'Format: one finished painting in landscape (horizontal) orientation, aspect ratio 3:2.',
  ].join('\n');
}

export const CODE_WIDTH = 1536;
export const CODE_HEIGHT = 1024;
export const CODE_MAX_BYTES = 200_000;

/** The prompt a text model receives (The Gallery Masterpiece: Painted in Code). */
export function codePrompt(b: Brief): string {
  return [
    'The museum commissions an original painting from you for its gallery. You paint in code: your painting is a single SVG image, which the museum renders exactly as written.',
    '',
    briefText(b),
    '',
    'Output rules:',
    `- Reply with a single \`\`\`svg code block containing one SVG document and nothing else.`,
    `- The root element must be <svg xmlns="http://www.w3.org/2000/svg" width="${CODE_WIDTH}" height="${CODE_HEIGHT}" viewBox="0 0 ${CODE_WIDTH} ${CODE_HEIGHT}">; the painting fills this landscape canvas.`,
    '- Use any SVG shapes, paths, gradients, patterns, clip paths, masks and filters (feTurbulence, feDisplacementMap, blurs) to give it painterly texture and light.',
    '- No <text> elements, no <image>, no <script>, no <foreignObject>, no external references or fonts, no animation.',
    `- Keep the file under ${Math.round(CODE_MAX_BYTES / 1000)} kB.`,
  ].join('\n');
}
