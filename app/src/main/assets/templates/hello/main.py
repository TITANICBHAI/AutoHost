from autohost import ctx, log


def main():
    steps = int(ctx.config.get("steps", 5))
    greeting = ctx.config.get("greeting", "Hello")
    runs = int(ctx.task_store.get("runs", 0)) + 1
    ctx.task_store["runs"] = runs
    for i in range(1, steps + 1):
        log.info(f"{greeting} from step {i}/{steps}", run_count=runs)
        ctx.progress(i / steps, f"step {i}")
        ctx.sleep(1)  # cancellable
    return ctx.result("ok", {"runs": runs})
