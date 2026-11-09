import * as THREE from 'three';

// Repeated flowers and rocks share geometry and are submitted as instance batches.
export function createCoastalDetails({ scene, world, palette, mat, mesh, box, sphere, rod, textSign }) {
  const pink = mat('#eaa3ac'), yellow = mat('#efd18a'), sage = mat('#9bad71');
  const terra = mat('#c4a17c'), stone = mat('#c9c5af');
  const catGray = mat('#59656d');
  const batches = new Map();
  const petalGeometry = new THREE.SphereGeometry(1, 7, 5);
  const rockGeometry = new THREE.DodecahedronGeometry(1, 0);
  const matrix = new THREE.Object3D();
  function instance(material, position, scale, rotation = 0, geometry = petalGeometry) {
    const key = `${material.uuid}:${geometry.uuid}`;
    if (!batches.has(key)) batches.set(key, { material, geometry, transforms: [] });
    matrix.position.set(...position); matrix.scale.set(...scale); matrix.rotation.set(0, rotation, rotation * .2); matrix.updateMatrix();
    batches.get(key).transforms.push(matrix.matrix.clone());
  }
  function flowers(x, y, z, width = 1, count = 5) {
    for (let i = 0; i < count; i++) {
      const px = x + (i / Math.max(1, count - 1) - .5) * width;
      const py = y + .12 + Math.sin(i * 2.3) * .08, pz = z + Math.cos(i * 3.1) * .13;
      for (let j = 0; j < 3; j++) {
        const a = i + j * 2.1;
        instance(j % 2 ? palette.leaf : sage, [px + Math.cos(a) * .15, py - .08, pz + Math.sin(a) * .15], [.17, .065, .1], a);
      }
      for (let j = 0; j < 5; j++) {
        const a = j * Math.PI * 2 / 5;
        instance(i % 3 ? pink : palette.white, [px + Math.cos(a) * .085, py + .06, pz + Math.sin(a) * .085], [.075, .045, .075]);
      }
      instance(yellow, [px, py + .09, pz], [.04, .04, .04]);
    }
  }
  function planter(x, y, z, width = .9) {
    box(width, .24, .36, terra, x, y, z, world);
    box(width + .06, .06, .41, palette.wood, x, y + .1, z, world);
    flowers(x, y + .16, z, width * .8, 5);
  }
  function pot(x, z, scale = 1) {
    mesh(new THREE.CylinderGeometry(.34 * scale, .26 * scale, .5 * scale, 10), terra, x, .25 * scale, z, world);
    flowers(x, .6 * scale, z, .5 * scale, 7);
  }
  function decorateBuilding(x, z, w, h, isFlowerShop) {
    // Roof parapet and window boxes face the promenade.
    box(w, .22, .14, palette.white, x, h + .15, z + 1.7, world);
    box(.14, .22, 3.5, palette.white, x - w / 2, h + .15, z, world);
    box(.14, .22, 3.5, palette.white, x + w / 2, h + .15, z, world);
    for (let floor = 2.5; floor < h - .6; floor += 1.8) {
      for (let col = -w / 2 + .7; col < w / 2 - .3; col += 1.3) {
        if (Math.round(col * 10 + floor * 10) % 3 !== 0) planter(x + col, floor - .45, z + 2, .75);
      }
    }
    if (!isFlowerShop) return;
    for (let i = 0; i < 7; i++) {
      flowers(x - w / 2 + i * .65, 2.65, z + 1.9, .45, 4);
      for (let j = 0; i === 0 && j < 8; j++) {
        instance(sage, [x + w / 2 - .1 + Math.sin(j * 1.7) * .17, 2.65 - j * .26, z + 1.95], [.16, .09, .12], j);
      }
    }
    for (let i = 0; i < 4; i++) pot(x - w / 2 + i * .75, z + 2.7, .8 + (i % 2) * .25);
    // Shop window mullions and warm pendant lamps.
    const lamp = mat('#f0ca7e', { emissive: '#e9a54e', emissiveIntensity: .3 });
    for (let i = -1; i <= 1; i++) {
      box(.07, 1.5, .12, palette.teal, x + i, .85, z + 1.96, world);
      mesh(new THREE.ConeGeometry(.13, .17, 8), lamp, x + i * .95, 1.3, z + 2.02, world);
      rod([x + i * .95, 1.4, z + 2], [x + i * .95, 1.65, z + 2], .012, palette.wood, world);
    }
    const sign = new THREE.Group(); world.add(sign); sign.position.set(x + 1.5, 0, z + 3.4);
    box(.65, .85, .09, palette.wood, 0, .55, 0, sign);
    box(.52, .65, .02, palette.ink, 0, .58, .06, sign);
    textSign('花 · 日常', .46, .18, '#f3e9cd', '#25484c', 0, .65, .08, sign);
    for (const dx of [-.26, .26]) rod([dx, 0, .3], [dx, .95, -.05], .035, palette.wood, sign);
  }
  function cat(x, z, sleeping = false, patch = palette.orange) {
    const group = new THREE.Group(); world.add(group); group.position.set(x, .1, z);
    const grayCoat = patch === catGray;
    if (grayCoat) group.scale.setScalar(1.4);
    sphere(.24, grayCoat ? patch : palette.white, 0, .22, 0, group, [1.6, sleeping ? .7 : 1.35, .85]);
    if (grayCoat) sphere(.16, palette.white, .23, .3, .06, group, [.7, 1.25, 1.15]);
    sphere(.18, grayCoat ? patch : palette.white, .22, sleeping ? .2 : .52, 0, group);
    if (grayCoat) sphere(.12, palette.white, .32, .48, .045, group, [.85, .8, 1.1]);
    for (const dz of [-.1, .1]) {
      mesh(new THREE.ConeGeometry(.075, .17, 4), palette.coral, .22, sleeping ? .37 : .69, dz, group);
      sphere(.023, palette.ink, .38, sleeping ? .23 : .55, dz * .75, group);
      sphere(.07, palette.white, .12, .045, dz, group, [1.6, .6, 1]);
    }
    sphere(.11, patch, -.13, .35, .13, group, [1.2, 1, .4]);
    if (!sleeping) sphere(.13, patch, -.12, .3, -.1, group, [1.4, 1.4, .5]);
    const curve = new THREE.CatmullRomCurve3([new THREE.Vector3(-.25, .18, 0), new THREE.Vector3(-.52, .22, .05), new THREE.Vector3(-.6, .43, .05)]);
    mesh(new THREE.TubeGeometry(curve, 8, .035, 6), patch, 0, 0, 0, group);
  }
  function kiosk(x, z) {
    box(.8, 1.5, .55, yellow, x, .75, z, world);
    box(.62, .86, .04, palette.glass, x, .99, z + .29, world);
    for (let i = 0; i < 6; i++) {
      mesh(new THREE.CylinderGeometry(.065, .065, .24, 8), i % 2 ? palette.white : palette.coral, x - .2 + (i % 3) * .2, .8 + Math.floor(i / 3) * .33, z + .32, world);
    }
    box(.3, .12, .03, palette.ink, x, .3, z + .3, world);
    box(.42, .65, .43, palette.blue, x + .7, .33, z, world);
    box(.46, .1, .46, palette.teal, x + .7, .7, z, world);
  }
  function addStreetDetails() {
    for (const [stop, x] of [3, 23, 47, 70].entries()) {
      kiosk(x + 5, -2.7); pot(x + 2, 6.5, 1.3);
      if (stop === 0) {
        cat(x - .7, 6.7, false, catGray);
        for (let i = 0; i < 3; i++) box(.85, .12, .6, terra, x - 3, .12 + i * .14, 6.3, world);
        for (let i = 0; i < 4; i++) mesh(new THREE.CylinderGeometry(.1, .09, .085, 12), i % 2 ? palette.white : palette.coral, x - 2.8, .5 + i * .085, 6.3, world);
        rod([x - 2.8, .8, 6.3], [x - 2.74, 1.02, 6.3], .015, palette.coral, world);
      } else if (stop === 1) {
        // Flower basket: a different silhouette at the service stop.
        mesh(new THREE.CylinderGeometry(.45, .34, .45, 12), terra, x - 1, .23, 6.4, world);
        const handle = mesh(new THREE.TorusGeometry(.41, .035, 6, 20, Math.PI), palette.wood, x - 1, .42, 6.4, world);
        handle.rotation.y = .3;
        flowers(x - 1, .53, 6.4, .65, 9);
      } else if (stop === 2) {
        cat(x - 1, 6.4, true, palette.orange);
        mesh(new THREE.CylinderGeometry(.18, .13, .08, 14), palette.teal, x - .4, .06, 6.5, world);
      } else {
        // A leaning lifebuoy and coiled rope replace the animal at arrival.
        const ring = mesh(new THREE.TorusGeometry(.4, .1, 8, 24), palette.white, x - 1, .48, 6.6, world);
        ring.rotation.x = -.2;
        for (let i = 0; i < 4; i++) {
          const stripe = mesh(new THREE.TorusGeometry(.4, .103, 6, 5, .42), palette.orange, 0, 0, 0, ring); stripe.rotation.z = i * Math.PI / 2;
        }
        for (let i = 0; i < 3; i++) {
          const rope = mesh(new THREE.TorusGeometry(.23 + i * .04, .025, 6, 20), terra, x - .4, .04 + i * .02, 6.7, world); rope.rotation.x = Math.PI / 2;
        }
      }
    }
    // Stone quay runs along the foreground edge; the ocean extends around it.
    box(210, 1.15, .25, stone, 30, -.65, 7.48, world);
    box(210, .13, .42, palette.cream, 30, -.03, 7.48, world);
    for (let i = 0; i < 110; i++) {
      const x = -45 + i * 1.4;
      instance(i % 2 ? stone : palette.sand, [x, -1.1, 8.1 + Math.sin(i * 2) * .3], [.65 + (i % 3) * .16, .4 + (i % 4) * .09, .6], i * 1.7, rockGeometry);
      box(.018, .9, .015, terra, x, -.6, 7.62, world);
      if (i % 3 === 0) {
        const foam = mesh(new THREE.TorusGeometry(.7, .018, 4, 12, Math.PI), palette.white, x, -1.52, 8.7, world);
        foam.rotation.x = -Math.PI / 2; foam.castShadow = false;
      }
      if (i % 11 === 0) {
        const ring = mesh(new THREE.TorusGeometry(.33, .085, 8, 24), palette.white, x, -.45, 7.72, world);
        for (let j = 0; j < 4; j++) { const stripe = mesh(new THREE.TorusGeometry(.33, .088, 6, 4, .35), palette.orange, 0, 0, .002, ring); stripe.rotation.z = j * Math.PI / 2; }
        mesh(new THREE.CylinderGeometry(.18, .2, .3, 10), palette.ink, x + 1, .15, 6.95, world);
        const rope = mesh(new THREE.TorusGeometry(.24, .035, 6, 20), terra, x + 1, .06, 6.95, world); rope.rotation.x = Math.PI / 2;
      }
    }
    // Lighthouse is fixed in the distance, independent of scrolling street geometry.
    const lighthouse = new THREE.Group(); lighthouse.position.set(7, -1.5, -19); scene.add(lighthouse);
    for (let i = 0; i < 9; i++) { const a = i * 2.4; const rock = mesh(rockGeometry, stone, Math.cos(a) * 2, .1, Math.sin(a) * 1.3, lighthouse); rock.scale.set(1.3, .6, .9); }
    mesh(new THREE.CylinderGeometry(.32, .53, 3.8, 12), palette.white, 0, 2, 0, lighthouse);
    mesh(new THREE.CylinderGeometry(.57, .57, .13, 12), palette.cream, 0, 3.7, 0, lighthouse);
    mesh(new THREE.CylinderGeometry(.32, .32, .5, 10), palette.glass, 0, 4.05, 0, lighthouse);
    mesh(new THREE.ConeGeometry(.57, .65, 10), palette.coral, 0, 4.62, 0, lighthouse);
    box(.18, .45, .05, palette.ink, 0, .8, .48, lighthouse);
  }
  const gulls = [];
  function addGulls() {
    for (let i = 0; i < 3; i++) {
      const group = new THREE.Group(); scene.add(group); group.position.set(4 + i * 5, 7 + i % 2, -10 - i * 5);
      sphere(.24, palette.white, 0, 0, 0, group, [1.5, .75, .8]);
      sphere(.16, palette.white, .3, .13, 0, group);
      rod([.43, .12, 0], [.65, .08, 0], .04, yellow, group);
      sphere(.022, palette.ink, .39, .17, .13, group);
      const wings = [];
      for (const side of [-1, 1]) {
        const wing = new THREE.Group(); group.add(wing); wings.push(wing);
        sphere(.4, palette.white, -.1, .04, side * .42, wing, [.65, .12, 1.5]);
        sphere(.24, palette.ink, -.22, .02, side * .91, wing, [.7, .1, 1.4]);
      }
      gulls.push({ group, wings, y: group.position.y, x: group.position.x });
    }
  }
  function finish() {
    for (const { material, geometry, transforms } of batches.values()) {
      const batch = new THREE.InstancedMesh(geometry, material, transforms.length);
      transforms.forEach((transform, i) => batch.setMatrixAt(i, transform));
      batch.castShadow = true; batch.receiveShadow = true; world.add(batch);
    }
  }
  function animate(time) {
    gulls.forEach(({ group, wings, y, x }, i) => {
      group.position.y = y + Math.sin(time * .7 + i) * .12;
      group.position.x = x + Math.sin(time * .2 + i) * .6;
      wings.forEach((wing, j) => { wing.rotation.x = Math.sin(time * 1.8 + i) * .16 * (j ? 1 : -1); });
    });
  }
  return { decorateBuilding, addStreetDetails, addGulls, finish, animate };
}
