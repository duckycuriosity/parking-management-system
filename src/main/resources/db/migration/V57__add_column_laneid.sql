ALTER TABLE barriers ADD COLUMN lane_id BIGINT;

ALTER TABLE barriers
    ADD CONSTRAINT fk_barrier_lane
        FOREIGN KEY (lane_id) REFERENCES lane(id);