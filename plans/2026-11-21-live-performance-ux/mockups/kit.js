/* Mockup kit: draws laser pictures into preview boxes.
   <div class="screen" data-shape="circle" data-color="#39ff6a" style="width:200px;height:150px"></div>
   data-shape: circle square triangle star spiral wave fan beams tunnel line text grid dots ring2 none
   data-color: any CSS colour (default laser green). data-scale: 0.2..1.5 (default 1).
   data-rot: degrees. data-text: the text for data-shape="text".
   data-half: "left" | "right" draws only that half (a split laser).
   data-lbl: top-left label; data-dot: green|red|orange|blue|grey; data-lbl2: bottom-left label.
   data-blocked: "x,y,w,h" in 0..100 units, a hatched blocked safety box (several with ';').
   data-limit: "x,y,w,h" the scan limit (dashed outline).
   Runs once on load; the screenshot is taken after. */
(function () {
  var NS = "http://www.w3.org/2000/svg";
  function el(name, attrs, parent) {
    var e = document.createElementNS(NS, name);
    for (var k in attrs) e.setAttribute(k, attrs[k]);
    if (parent) parent.appendChild(e);
    return e;
  }
  function poly(points) { return points.map(function (p) { return p[0].toFixed(2) + "," + p[1].toFixed(2); }).join(" "); }
  function draw(box) {
    var shape = box.getAttribute("data-shape") || "none";
    var color = box.getAttribute("data-color") || "#39ff6a";
    var scale = parseFloat(box.getAttribute("data-scale") || "1");
    var rot = parseFloat(box.getAttribute("data-rot") || "0");
    var half = box.getAttribute("data-half");
    var W = 100, H = 75, cx = 50, cy = 37.5, r = 22 * scale;
    var svg = el("svg", { viewBox: "0 0 100 75", preserveAspectRatio: "xMidYMid meet" });
    var defs = el("defs", {}, svg);
    if (half) {
      var cp = el("clipPath", { id: "c" + Math.random().toString(36).slice(2) }, defs);
      el("rect", half === "left" ? { x: 0, y: 0, width: 50, height: 75 } : { x: 50, y: 0, width: 50, height: 75 }, cp);
      svg.dataset.clip = cp.id;
    }
    var g = el("g", { transform: "rotate(" + rot + " " + cx + " " + cy + ")", stroke: color, fill: "none",
                      "stroke-width": 0.9, "stroke-linecap": "round", "stroke-linejoin": "round",
                      style: "color:" + color + ";filter:drop-shadow(0 0 1.2px " + color + ") drop-shadow(0 0 3px " + color + ")" }, svg);
    if (svg.dataset.clip) g.setAttribute("clip-path", "url(#" + svg.dataset.clip + ")");
    var i, pts;
    switch (shape) {
      case "circle": el("circle", { cx: cx, cy: cy, r: r }, g); break;
      case "ring2": el("circle", { cx: cx, cy: cy, r: r }, g); el("circle", { cx: cx, cy: cy, r: r * 0.55 }, g); break;
      case "square": el("rect", { x: cx - r, y: cy - r, width: 2 * r, height: 2 * r }, g); break;
      case "triangle": el("polygon", { points: poly([[cx, cy - r], [cx + r * 0.95, cy + r * 0.75], [cx - r * 0.95, cy + r * 0.75]]) }, g); break;
      case "star":
        pts = []; for (i = 0; i < 10; i++) { var a = -Math.PI / 2 + i * Math.PI / 5, rr = i % 2 ? r * 0.42 : r; pts.push([cx + rr * Math.cos(a), cy + rr * Math.sin(a)]); }
        el("polygon", { points: poly(pts) }, g); break;
      case "spiral":
        pts = []; for (i = 0; i < 220; i++) { var t = i / 220 * Math.PI * 7, rs = 1 + r * i / 220; pts.push([cx + rs * Math.cos(t), cy + rs * Math.sin(t)]); }
        el("polyline", { points: poly(pts) }, g); break;
      case "wave":
        pts = []; for (i = 0; i <= 120; i++) { var x = 10 + i * 80 / 120; pts.push([x, cy + Math.sin(i / 120 * Math.PI * 4) * 12 * scale]); }
        el("polyline", { points: poly(pts) }, g); break;
      case "line": el("line", { x1: 12, y1: cy, x2: 88, y2: cy }, g); break;
      case "fan": case "beams":
        var n = shape === "fan" ? 9 : 5;
        for (i = 0; i < n; i++) { var ang = (-60 + i * 120 / (n - 1)) * Math.PI / 180; el("line", { x1: cx, y1: 72, x2: cx + Math.sin(ang) * 70 * scale, y2: 72 - Math.cos(ang) * 70 * scale }, g); }
        break;
      case "tunnel": for (i = 1; i <= 5; i++) el("rect", { x: cx - i * 5 * scale, y: cy - i * 3.6 * scale, width: i * 10 * scale, height: i * 7.2 * scale, rx: 1 }, g); break;
      case "grid": for (i = 0; i <= 6; i++) { el("line", { x1: 15 + i * 70 / 6, y1: 10, x2: 15 + i * 70 / 6, y2: 65 }, g); el("line", { x1: 15, y1: 10 + i * 55 / 6, x2: 85, y2: 10 + i * 55 / 6 }, g); } break;
      case "dots": for (i = 0; i < 14; i++) { var da = i / 14 * Math.PI * 2; el("circle", { cx: cx + Math.cos(da) * r, cy: cy + Math.sin(da) * r, r: 0.9, fill: color }, g); } break;
      case "text":
        var tx = el("text", { x: cx, y: cy + 5, "text-anchor": "middle", "font-size": 15 * scale, "font-weight": 700, fill: "none", stroke: color, "stroke-width": 0.6, "font-family": "Arial" }, g);
        tx.textContent = box.getAttribute("data-text") || "LASER"; break;
    }
    var lim = box.getAttribute("data-limit");
    if (lim) { var l = lim.split(",").map(Number); el("rect", { x: l[0], y: l[1] * 0.75, width: l[2], height: l[3] * 0.75, fill: "none", stroke: "#ffd400", "stroke-width": 0.6, "stroke-dasharray": "2 1.5" }, svg); }
    var blk = box.getAttribute("data-blocked");
    if (blk) blk.split(";").forEach(function (s, idx) {
      var b = s.split(",").map(Number);
      var pid = "h" + Math.random().toString(36).slice(2);
      var pat = el("pattern", { id: pid, width: 3, height: 3, patternUnits: "userSpaceOnUse", patternTransform: "rotate(45)" }, defs);
      el("rect", { width: 1.4, height: 3, fill: "rgba(253,54,54,.7)" }, pat);
      el("rect", { x: b[0], y: b[1] * 0.75, width: b[2], height: b[3] * 0.75, fill: "url(#" + pid + ")", stroke: "#fd3636", "stroke-width": 0.6 }, svg);
    });
    box.appendChild(svg);
    var lbl = box.getAttribute("data-lbl");
    if (lbl) {
      var d = document.createElement("div"); d.className = "lbl";
      var dot = box.getAttribute("data-dot");
      d.innerHTML = (dot ? '<span class="dot ' + dot + '"></span>' : "") + lbl;
      box.appendChild(d);
    }
    var lbl2 = box.getAttribute("data-lbl2");
    if (lbl2) { var d2 = document.createElement("div"); d2.className = "lbl2"; d2.innerHTML = lbl2; box.appendChild(d2); }
  }
  function run() { Array.prototype.forEach.call(document.querySelectorAll(".screen[data-shape]"), draw); }
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", run); else run();
})();
