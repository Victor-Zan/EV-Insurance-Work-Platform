import * as THREE from 'three';

// Reference-inspired character; local coordinates match the scooter seat and grips.
export function createRider({ scooter, palette, mat, mesh, sphere, rod, box }) {
  const pink = mat('#e58c9e'), pinkLight = mat('#efa0ad');
  const skin = mat('#f0c5a1'), blush = mat('#e8a79d');
  const shirt = mat('#faf5e8'), bagMaterial = mat('#ead9b7');
  const frameMaterial = mat('#443e39');
  const canvas = document.createElement('canvas'); canvas.width = canvas.height = 256;
  const ctx = canvas.getContext('2d');
  ctx.fillStyle = '#e8ad48'; ctx.fillRect(0, 0, 256, 256);
  for (const [x, y] of [[48, 48], [174, 98], [70, 185], [210, 220]]) {
    ctx.fillStyle = '#fff5da';
    for (let i = 0; i < 5; i++) {
      const a = i * Math.PI * 2 / 5;
      ctx.beginPath(); ctx.ellipse(x + Math.cos(a) * 8, y + Math.sin(a) * 8, 5, 7, a, 0, Math.PI * 2); ctx.fill();
    }
    ctx.fillStyle = '#bd7f31'; ctx.beginPath(); ctx.arc(x, y, 3, 0, Math.PI * 2); ctx.fill();
  }
  const flowers = new THREE.CanvasTexture(canvas); flowers.colorSpace = THREE.SRGBColorSpace;
  flowers.wrapS = flowers.wrapT = THREE.RepeatWrapping;
  const shorts = mat('#ffffff', { map: flowers });
  for (const side of [-1, 1]) {
    const z = side * .22;
    rod([-.59, 1.23, z], [-.18, 1.11, z], .145, shorts, scooter);
    rod([-.18, 1.11, z], [.07, 1.04, z], .105, skin, scooter);
    sphere(.11, skin, .07, 1.04, z, scooter);
    rod([.07, 1.04, z], [.12, .62, z], .085, skin, scooter);
    sphere(.13, shirt, .22, .57, z, scooter, [1.6, .48, .78]);
    box(.38, .035, .21, palette.cream, .24, .52, z, scooter);
    for (let i = 0; i < 3; i++) rod([.17 + i * .045, .63, z - .06], [.17 + i * .045, .63, z + .06], .012, bagMaterial, scooter);
    // Short sleeves, elbows and bare forearms meet the existing handlebar grips.
    rod([-.29, 1.89, side * .22], [-.08, 1.71, side * .29], .10, shirt, scooter);
    rod([-.08, 1.71, side * .29], [.16, 1.59, side * .33], .067, skin, scooter);
    sphere(.07, skin, .16, 1.59, side * .33, scooter);
    rod([.16, 1.59, side * .33], [.62, 1.66, side * .35], .057, skin, scooter);
    sphere(.078, skin, .62, 1.66, side * .35, scooter, [1, .7, 1]);
    sphere(.04, skin, .66, 1.63, side * .30, scooter);
  }
  const torso = mesh(new THREE.CapsuleGeometry(.22, .4, 6, 16), shirt, -.43, 1.62, 0, scooter);
  torso.rotation.z = -.17;
  rod([-.37, 1.9, 0], [-.29, 2.08, 0], .085, skin, scooter);
  // Face is turned slightly toward the viewer, keeping both round lenses readable.
  const head = new THREE.Group(); head.position.set(-.24, 2.24, 0); head.rotation.y = 1.02; scooter.add(head);
  sphere(.31, skin, 0, 0, 0, head, [.95, 1.1, .9]);
  for (const side of [-1, 1]) {
    sphere(.065, skin, side * .29, -.015, 0, head, [.6, 1, .8]);
    sphere(.043, blush, side * .17, -.085, .239, head, [1, .5, .2]);
    sphere(.044, shirt, side * .12, .025, .258, head, [.8, 1, .3]);
    sphere(.026, frameMaterial, side * .12, .025, .272, head, [.75, 1.2, .35]);
    sphere(.009, shirt, side * .12 - .006, .038, .282, head);
    const lens = mesh(new THREE.TorusGeometry(.103, .014, 8, 28), frameMaterial, side * .122, .035, .29, head); lens.castShadow = false;
    rod([side * .225, .05, .29], [side * .29, .05, -.02], .012, frameMaterial, head);
    rod([side * .07, .145, .247], [side * .16, .15, .24], .012, pink, head);
  }
  rod([-.02, .055, .30], [.02, .055, .30], .013, frameMaterial, head);
  sphere(.045, skin, 0, -.035, .29, head, [.65, .8, 1]);
  const smile = new THREE.CatmullRomCurve3([new THREE.Vector3(-.052, -.14, .251), new THREE.Vector3(0, -.156, .263), new THREE.Vector3(.052, -.14, .251)]);
  mesh(new THREE.TubeGeometry(smile, 12, .008, 5), frameMaterial, 0, 0, 0, head);
  // Rounded crown, swept fringe and separately moving long locks.
  sphere(.33, pink, 0, .18, -.045, head, [1.04, .64, .94]);
  const fringe = sphere(.19, pinkLight, -.13, .17, .18, head, [.75, 1.15, .42]); fringe.rotation.z = -.65;
  sphere(.17, pink, .23, .06, -.015, head, [.5, 1.6, .9]);
  const hair = new THREE.Group(); hair.position.set(-.47, 2.33, -.03); scooter.add(hair);
  for (let i = 0; i < 6; i++) {
    const z = -.22 + i * .085;
    const curve = new THREE.CatmullRomCurve3([
      new THREE.Vector3(0, .04 - (i % 2) * .08, z),
      new THREE.Vector3(-.24, -.12 - i * .025, z * 1.2),
      new THREE.Vector3(-.55, -.16 - i * .025, z * 1.3),
      new THREE.Vector3(-.8 + (i % 3) * .07, -.05 - i * .035, z * 1.4)
    ]);
    mesh(new THREE.TubeGeometry(curve, 16, .075, 8), i % 3 ? pink : pinkLight, 0, 0, 0, hair);
    sphere(.075, pink, -.8 + (i % 3) * .07, -.05 - i * .035, z * 1.4, hair);
  }
  // Cream cross-body bag with flap, clasp and a continuous shoulder strap.
  const bag = new THREE.Group(); bag.position.set(-.72, 1.3, .31); bag.rotation.z = -.2; scooter.add(bag);
  const body = mesh(new THREE.CapsuleGeometry(.14, .15, 4, 12), bagMaterial, 0, 0, 0, bag); body.rotation.z = Math.PI / 2; body.scale.z = .55;
  box(.32, .13, .035, palette.cream, 0, .07, .095, bag);
  box(.035, .065, .018, palette.wood, 0, .005, .12, bag);
  rod([-.3, 1.94, .23], [-.6, 1.48, .29], .023, bagMaterial, scooter);
  rod([-.6, 1.48, .29], [-.75, 1.38, .32], .023, bagMaterial, scooter);
  return { animate(time) { hair.rotation.y = Math.sin(time * 2.3) * .035; hair.rotation.z = Math.sin(time * 2.8) * .025; } };
}
