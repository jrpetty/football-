// A bounded, drop-oldest queue for volatile frames. When analysis falls
// behind, the oldest frame is discarded (and released) rather than letting
// frames — or latency — pile up. Old frames are worth less than new ones.

export interface QueueStats {
  readonly enqueued: number
  readonly dropped: number
  readonly depth: number
  readonly capacity: number
}

export class BoundedFrameQueue<T> {
  private readonly items: T[] = []
  private readonly capacity: number
  private readonly release: (item: T) => void
  private enqueued = 0
  private dropped = 0

  constructor(capacity: number, release: (item: T) => void = () => {}) {
    if (!Number.isInteger(capacity) || capacity < 1) throw new Error('capacity must be a positive integer')
    this.capacity = capacity
    this.release = release
  }

  push(item: T): void {
    this.enqueued++
    this.items.push(item)
    while (this.items.length > this.capacity) {
      this.dropped++
      this.release(this.items.shift()!)
    }
  }

  shift(): T | undefined {
    return this.items.shift()
  }

  /** Release everything, e.g. when capture stops. */
  clear(): void {
    for (const item of this.items.splice(0)) this.release(item)
  }

  get stats(): QueueStats {
    return { enqueued: this.enqueued, dropped: this.dropped, depth: this.items.length, capacity: this.capacity }
  }
}
