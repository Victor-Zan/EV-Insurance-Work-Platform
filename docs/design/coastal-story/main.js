import * as THREE from 'three';
import { createCoastalDetails } from './scenery-details.js';
import { createRider } from './rider.js';

const sceneHost = document.querySelector('#scene');
const status = document.querySelector('#render-status');
const chapters = [...document.querySelectorAll('[data-chapter]')];
const reduced = matchMedia('(prefers-reduced-motion: reduce)');
let paused = reduced.matches;
reduced.addEventListener('change', e => { paused = e.matches; });

let progress = 0;
function updateScroll() {
  const height = document.documentElement.scrollHeight - innerHeight;
  progress = height > 0 ? Math.min(1, Math.max(0, scrollY / height)) : 0;

  chapters.forEach(chapter => {
    const distance = Math.abs(chapter.getBoundingClientRect().top) / innerHeight;
    const copy = chapter.querySelector('.copy');
    copy.style.opacity = Math.max(0, 1 - distance * 1.5);

  });
}
addEventListener('scroll', updateScroll, { passive: true });
updateScroll();

async function start() {
  const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: false, powerPreference: 'low-power' });
  renderer.setPixelRatio(Math.min(devicePixelRatio, 1.7));
  renderer.shadowMap.enabled = true;
  renderer.shadowMap.type = THREE.PCFSoftShadowMap;
  renderer.outputColorSpace = THREE.SRGBColorSpace;
  renderer.toneMapping = THREE.ACESFilmicToneMapping;
  renderer.toneMappingExposure = 1.12;
  renderer.setClearColor('#e1ebdb');
  sceneHost.append(renderer.domElement);
  renderer.domElement.addEventListener('webglcontextlost', e => {
    e.preventDefault(); status.textContent = '画面连接已中断，请刷新恢复。'; status.hidden = false;
  });
  const scene = new THREE.Scene();
  scene.background = new THREE.Color('#dfece3');
  scene.fog = new THREE.Fog('#dfece3', 45, 120);
  const camera = new THREE.PerspectiveCamera(36, 1, .1, 200);
  const ambient = new THREE.HemisphereLight('#fff6df', '#6d9b90', 2.7);
  scene.add(ambient);
  const sun = new THREE.DirectionalLight('#fff0df', 2.4);
  sun.position.set(-12, 24, 15); sun.castShadow = true;
  sun.shadow.mapSize.set(2048, 2048);
  Object.assign(sun.shadow.camera, { left: -30, right: 30, top: 22, bottom: -22, near: .5, far: 90 });
  sun.shadow.normalBias = .04;
  sun.shadow.radius = 3;
  scene.add(sun);
  const mat = (color, extra = {}) => new THREE.MeshStandardMaterial({ color, roughness: .86, ...extra });
  const palette = {
    cream: mat('#eee5c9'), sand: mat('#d8d5b9'), road: mat('#617b77'), curb: mat('#e7debf'),
    coral: mat('#da7959'), orange: mat('#ed6b35'), ink: mat('#25484c'), white: mat('#f5efdb'),
    glass: mat('#608f91', { metalness: .15, roughness: .3 }), teal: mat('#529f95'),
    tire: mat('#253a3d'), chrome: mat('#b1c5be', { metalness: .65, roughness: .32 }),
    leaf: mat('#6d9d77'), darkLeaf: mat('#397e70'), wood: mat('#9b8770'), blue: mat('#abc7c1'),
    skin: mat('#dba882'), pants: mat('#385c65'), jacket: mat('#ebd6b3')
  };
  function mesh(geometry, material, x, y, z, parent = scene) {
    const object = new THREE.Mesh(geometry, material);
    object.position.set(x, y, z); object.castShadow = true; object.receiveShadow = true;
    parent.add(object); return object;
  }
  function box(w, h, d, material, x, y, z, parent = scene) { return mesh(new THREE.BoxGeometry(w, h, d), material, x, y, z, parent); }
  function sphere(radius, material, x, y, z, parent = scene, scale) {
    const o = mesh(new THREE.SphereGeometry(radius, 12, 8), material, x, y, z, parent);
    if (scale) o.scale.set(...scale); return o;
  }
  function rod(a, b, radius, material, parent = scene) {
    const from = new THREE.Vector3(...a), to = new THREE.Vector3(...b), delta = to.clone().sub(from);
    const o = mesh(new THREE.CylinderGeometry(radius, radius, delta.length(), 8), material, ...from.clone().add(to).multiplyScalar(.5).toArray(), parent);
    o.quaternion.setFromUnitVectors(new THREE.Vector3(0, 1, 0), delta.normalize()); return o;
  }
  function textSign(text, w, h, color, background, x, y, z, parent) {
    const canvas = document.createElement('canvas'); canvas.width = 512; canvas.height = 160;
    const ctx = canvas.getContext('2d'); ctx.fillStyle = background; ctx.fillRect(0, 0, 512, 160);
    ctx.fillStyle = color; ctx.font = '600 60px "Microsoft YaHei",sans-serif'; ctx.textAlign = 'center'; ctx.textBaseline = 'middle'; ctx.fillText(text, 256, 84);
    const texture = new THREE.CanvasTexture(canvas); texture.colorSpace = THREE.SRGBColorSpace;
    return mesh(new THREE.PlaneGeometry(w, h), new THREE.MeshBasicMaterial({ map: texture }), x, y, z, parent);
  }

  // A continuous promenade. Environment objects travel past the rider as the story scrolls.
  const world = new THREE.Group(); scene.add(world);
  const details = createCoastalDetails({ scene, world, palette, mat, mesh, box, sphere, rod, textSign });
  box(260, .4, 13, palette.sand, 35, -.36, 1, world);
  box(260, .08, 6.2, palette.road, 35, -.1, 2, world);
  box(260, .14, .2, palette.curb, 35, .02, -1.2, world);
  box(260, .14, .2, palette.curb, 35, .02, 5.2, world);
  for (let x = -70; x < 150; x += 4) box(1.8, .015, .08, palette.cream, x, -.048, 2, world);
  // Sea, distant islands and a quiet sailboat.
  const seaMat = mat('#74b8b0', { roughness: .55, metalness: .1 });
  box(300, .2, 300, seaMat, 25, -1.65, -35);
  const waves = new THREE.Group(); scene.add(waves);
  for (let i = 0; i < 65; i++) {
    const line = box(1.2 + (i % 5) * .7, .012, .035, mat(i % 3 ? '#b0d4c7' : '#91c5ba'), (i * 13.7) % 130 - 55, -1.53, -8 - ((i * 7.3) % 60), waves);
    line.castShadow = false;
  }
  const island = mat('#a1bcb0');
  for (let i = 0; i < 9; i++) { const o = mesh(new THREE.ConeGeometry(8 + i % 3 * 3, 5 + i % 4, 5), island, -55 + i * 17, -1.8, -83 - (i % 3) * 6); o.scale.z = .5; }
  const boat = new THREE.Group(); boat.position.set(6, -1.37, -15); scene.add(boat);
  const hull = mesh(new THREE.SphereGeometry(1, 8, 6), palette.cream, 0, 0, 0, boat); hull.scale.set(1.6, .23, .45);
  rod([0, 0, 0], [0, 3.1, 0], .035, palette.wood, boat);
  const sailShape = new THREE.Shape(); sailShape.moveTo(.08, .4); sailShape.lineTo(.08, 3); sailShape.lineTo(1.8, .4); sailShape.closePath();
  mesh(new THREE.ShapeGeometry(sailShape), new THREE.MeshStandardMaterial({ color: '#f5e8c9', side: THREE.DoubleSide }), 0, 0, 0, boat);

  function palm(x, z, scale = 1) {
    const group = new THREE.Group(); world.add(group); group.position.set(x, 0, z); group.scale.setScalar(scale);
    rod([0, 0, 0], [.2, 4.8, 0], .12, palette.wood, group);
    for (let i = 0; i < 7; i++) {
      const angle = i / 7 * Math.PI * 2;
      const path = new THREE.CatmullRomCurve3([new THREE.Vector3(.2, 4.8, 0), new THREE.Vector3(Math.cos(angle) * 1.1, 5.1, Math.sin(angle) * 1.1), new THREE.Vector3(Math.cos(angle) * 2.1, 4.25, Math.sin(angle) * 2.1)]);
      mesh(new THREE.TubeGeometry(path, 5, .2, 3, false), i % 2 ? palette.leaf : palette.darkLeaf, 0, 0, 0, group);
    }
  }
  function streetlamp(x, z) {
    rod([x, 0, z], [x, 4.2, z], .055, palette.ink, world);
    rod([x, 4.2, z], [x + .7, 4.2, z], .055, palette.ink, world);
    box(.8, .08, .3, palette.cream, x + .6, 4.15, z, world);
  }
  function building(x, z, w, h, material, name) {
    const group = new THREE.Group(); group.position.set(x, 0, z); world.add(group);
    box(w, h, 3.6, material, 0, h / 2, 0, group);
    box(w + .2, .18, 3.8, palette.cream, 0, h, 0, group);
    for (let floor = 2.5; floor < h - .6; floor += 1.8) {
      for (let col = -w / 2 + .7; col < w / 2 - .3; col += 1.3) {
        box(.68, .95, .08, palette.glass, col, floor, 1.83, group);
        box(.78, .08, .18, palette.cream, col, floor - .5, 1.9, group);
      }
    }
    box(w - .7, 1.7, .08, palette.glass, 0, .9, 1.85, group);
    if (name) {
      textSign(name, w - .3, .65, '#f5ecd5', '#315f5a', 0, 2.03, 1.93, group);
      const cloth = name.includes('花房') ? palette.leaf : palette.coral;
      const strips = Math.ceil(w / .4), stripWidth = (w + .15) / strips;
      for (let i = 0; i < strips; i++) {
        const color = i % 2 ? palette.white : cloth, x = -(w + .15) / 2 + stripWidth * (i + .5);
        const awning = box(stripWidth, .08, 1.05, color, x, 1.72, 2.23, group); awning.rotation.x = .18;
        box(stripWidth, .2, .07, color, x, 1.51, 2.74, group);
      }
    }
    details.decorateBuilding(x, z, w, h, Boolean(name?.includes('花房')));
    return group;
  }
  const buildings = [palette.cream, palette.coral, palette.blue, palette.sand];
  for (let i = 0; i < 19; i++) {
    const x = i * 6 - 48;
    // Open the seafront between small clusters of buildings.
    if (i % 5 < 3 && Math.abs(x - 25) > 5 && Math.abs(x - 48) > 6) building(x, -6.5, 4.5, 3.8 + (i % 4) * 1.5, buildings[i % 4], i % 3 === 0 ? '海风商店' : null);
    if (i % 2 === 0) palm(x + 2.8, -2.4, .75 + (i % 3) * .1);
    streetlamp(x + 2.3, 5.9);
  }
  building(0, -6.5, 4.5, 5.7, palette.cream, '海岸花房');
  building(68, -6.5, 4.5, 5.7, palette.cream, '海岸花房');
  details.addStreetDetails();
  // A repeated, light sea wall with separate posts rather than a solid visual barrier.
  for (let x = -65; x < 145; x += 2) {
    box(.1, .72, .1, palette.cream, x, .35, -4, world);
    box(2, .07, .08, palette.cream, x + 1, .65, -4, world);
    box(2, .05, .08, palette.cream, x + 1, .32, -4, world);
  }
  const service = building(25, -3.9, 4, 3.1, palette.cream, 'e保 · 服务站');
  box(.6, 1.6, .4, palette.teal, 2.6, .8, 1.9, service);
  textSign('i', .5, .7, '#f8f0dc', '#437b71', 2.6, 1.15, 2.12, service);
  const repair = building(48, -3.9, 6.5, 3.8, palette.teal, 'e保 · 维修协作');
  box(2.6, 2.1, .1, palette.ink, -.8, 1.1, 1.9, repair);
  for (let x = -1.8; x < .5; x += .3) box(.04, 2, .04, palette.chrome, x, 1.1, 2, repair);
  box(.75, 1.15, .6, palette.coral, 4.2, .6, 1.4, repair);
  for (let i = 0; i < 7; i++) {
    const x = -30 + i * 15;
    box(2.1, .13, .6, palette.wood, x, .55, 6.3, world);
    box(2.1, .65, .1, palette.wood, x, .9, 6.55, world);
    for (const dx of [-.7, .7]) box(.1, .55, .5, palette.ink, x + dx, .25, 6.3, world);
    box(1.2, .5, 1, palette.cream, x + 4, .25, 6, world);
    sphere(.55, palette.leaf, x + 4, .8, 6, world, [1, .8, .8]);
  }

  // Original low-poly electric scooter with a step-through frame.
  const scooter = new THREE.Group(); scene.add(scooter); scooter.position.set(4.2, 0, 2.4); scooter.scale.setScalar(1.35);
  const wheels = [];
  for (const x of [-.94, 1.04]) {
    const wheel = new THREE.Group(); wheel.position.set(x, .43, 0); scooter.add(wheel); wheels.push(wheel);
    mesh(new THREE.TorusGeometry(.33, .105, 8, 24), palette.tire, 0, 0, 0, wheel);
    const hub = mesh(new THREE.CylinderGeometry(.23, .23, .17, 16), palette.chrome, 0, 0, 0, wheel); hub.rotation.x = Math.PI / 2;
    mesh(new THREE.TorusGeometry(.18, .025, 5, 16), palette.ink, 0, 0, .1, wheel);
    for (let i = 0; i < 5; i++) {
      const angle = i * Math.PI * 2 / 5;
      rod([0, 0, .12], [Math.cos(angle) * .2, Math.sin(angle) * .2, .12], .023, palette.ink, wheel);
    }
    const fender = mesh(new THREE.TorusGeometry(.44, .07, 5, 16, Math.PI), palette.orange, x, .43, 0, scooter); fender.rotation.z = 0;
  }
  box(1.05, .17, .48, palette.orange, -.02, .45, 0, scooter);
  const tail = sphere(.55, palette.orange, -.72, .77, 0, scooter, [1.05, .7, .5]);
  box(.92, .18, .49, palette.ink, -.65, 1.08, 0, scooter);
  const shield = box(.3, .95, .47, palette.orange, .68, .98, 0, scooter); shield.rotation.z = .22;
  box(.09, .65, .1, palette.chrome, .96, .76, 0, scooter).rotation.z = .23;
  rod([.75, 1.15, 0], [.64, 1.67, 0], .06, palette.ink, scooter);
  rod([.64, 1.67, -.37], [.64, 1.67, .37], .045, palette.ink, scooter);
  const headlight = mesh(new THREE.CylinderGeometry(.14, .14, .2, 16), palette.cream, .84, 1.56, 0, scooter); headlight.rotation.z = Math.PI / 2;
  const lens = mesh(new THREE.CircleGeometry(.12, 16), mat('#fff6cf', { emissive: '#ffce70', emissiveIntensity: .4 }), .946, 1.56, 0, scooter); lens.rotation.y = Math.PI / 2;
  box(.08, .14, .31, palette.coral, -1.24, .83, 0, scooter);
  box(.06, .16, .26, palette.white, -1.25, .55, 0, scooter);
  for (const z of [-.34, .34]) {
    rod([.65, 1.65, z], [.76, 1.99, z * 1.4], .018, palette.chrome, scooter);
    sphere(.12, palette.ink, .76, 2.0, z * 1.4, scooter, [1, .7, .45]);
  }
  textSign('e', .26, .18, '#f5ead2', '#e76a35', -.7, .83, .284, scooter);
  const rider = createRider({ scooter, palette, mat, mesh, sphere, rod, box });
  details.addGulls();
  details.finish();

  let mobile = false;
  function resize() {
    mobile = innerWidth <= 700;
    camera.aspect = innerWidth / innerHeight;
    camera.fov = mobile ? 42 : 36;
    camera.updateProjectionMatrix(); renderer.setSize(innerWidth, innerHeight);
    updateScroll();
  }
  addEventListener('resize', resize); resize();
  let smooth = progress, previous = 0, elapsed = 0;
  const target = new THREE.Vector3();
  function frame(now) {
    const dt = Math.min((now - previous) / 1000 || 0, .05); previous = now;
    const moving = !paused && !document.hidden;
    if (moving) elapsed += dt;
    smooth += (progress - smooth) * (reduced.matches ? 1 : 1 - Math.exp(-dt * 6));
    world.position.x = -smooth * 69;
    const bob = moving ? Math.sin(elapsed * 5) * .014 : 0;
    scooter.position.y = bob;
    scooter.rotation.y = -.07 + Math.sin(smooth * Math.PI * 2) * .08;
    wheels.forEach(wheel => { if (moving) wheel.rotation.z -= dt * 2.6; });
    boat.rotation.z = Math.sin(elapsed * .7) * .025;
    waves.position.x = Math.sin(elapsed * .3) * .25;
    details.animate(elapsed);
    rider.animate(elapsed);
    if (mobile) {
      target.set(2.8, 3.8, 1);
      camera.position.set(12.8, 13.8, 23);
    } else {
      target.set(-1.2, 1.5, -1.5);
      camera.position.set(11.5 + Math.sin(smooth * Math.PI) * 1.5, 11, 22);
    }
    camera.lookAt(target); renderer.render(scene, camera);
    requestAnimationFrame(frame);
  }
  status.hidden = true;
  requestAnimationFrame(frame);
}
start().catch(error => {
  console.error('Coastal scene could not start', error);
  status.hidden = false;
  status.textContent = '当前设备无法显示三维场景，可继续阅读旅程介绍。';
});
