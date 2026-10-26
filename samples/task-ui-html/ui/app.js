/* Tier C bridge usage: autohost.invoke(handler, args) -> outcome JSON (synchronous). */
"use strict";

function render(outcome) {
  if (outcome && outcome.status === "SUCCEEDED" && outcome.data && typeof outcome.data.clicks === "number") {
    document.getElementById("count").textContent = String(outcome.data.clicks);
  } else {
    document.getElementById("count").textContent = "?";
    if (outcome && outcome.message) {
      document.title = "handler failed: " + (outcome.reason || outcome.message);
    }
  }
}

document.getElementById("plus").addEventListener("click", function () {
  render(JSON.parse(autohost.invoke("increment", JSON.stringify({ by: 1 }))));
});
document.getElementById("plus5").addEventListener("click", function () {
  render(JSON.parse(autohost.invoke("increment", JSON.stringify({ by: 5 }))));
});
document.getElementById("reset").addEventListener("click", function () {
  render(JSON.parse(autohost.invoke("reset", JSON.stringify({}))));
});

/* Run-status pushes from the host. */
window.autohostEvent = function (topic, payload) {
  if (topic === "run" && payload && payload.status) {
    document.getElementById("run").textContent = payload.status;
  }
};

/* Initial paint: read the host-side state if a handler published one. */
try {
  var state = JSON.parse(autohost.state());
  if (typeof state.clicks === "number") {
    document.getElementById("count").textContent = String(state.clicks);
  }
} catch (e) {
  /* first open: no state yet */
}
