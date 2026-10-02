ALTER TABLE public.parking_customer
    ADD COLUMN IF NOT EXISTS customer_code varchar (50),
    ADD COLUMN IF NOT EXISTS identity_number varchar (50),
    ADD COLUMN IF NOT EXISTS apartment_number varchar (50),
    ADD COLUMN IF NOT EXISTS address varchar (255),
    ADD COLUMN IF NOT EXISTS zalo_link varchar (255),
    ADD COLUMN IF NOT EXISTS is_zalo_linked boolean DEFAULT false,
    ADD COLUMN IF NOT EXISTS note text;

ALTER TABLE public.ticket
    ADD COLUMN IF NOT EXISTS ticket_code varchar (50),
    ADD COLUMN IF NOT EXISTS parking_lot_id bigint,
    ADD COLUMN IF NOT EXISTS price numeric(15,2);

ALTER TABLE public.ticket
    ADD CONSTRAINT fk_ticket_parking_lot
    FOREIGN KEY (parking_lot_id)
    REFERENCES public.parking_lot (id)
    ON DELETE SET NULL;

ALTER TABLE public.payment
    ADD COLUMN IF NOT EXISTS ticket_id bigint,
    ADD COLUMN IF NOT EXISTS period_start timestamp without time zone,
    ADD COLUMN IF NOT EXISTS period_end timestamp without time zone;

ALTER TABLE public.payment
    ADD CONSTRAINT fk_payment_ticket
    FOREIGN KEY (ticket_id)
    REFERENCES public.ticket (id)
    ON DELETE SET NULL;
CREATE INDEX IF NOT EXISTS idx_payment_ticket_id ON public.payment(ticket_id);

CREATE TABLE IF NOT EXISTS public.vehicle_users (

    id bigserial PRIMARY KEY,
    vehicle_id bigint NOT NULL,
    customer_id bigint NOT NULL,
    user_type varchar (50),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_vehicle_users_vehicle
    FOREIGN KEY (vehicle_id)
    REFERENCES public.vehicle (id)
    ON DELETE CASCADE,

    CONSTRAINT fk_vehicle_users_customer
    FOREIGN KEY (customer_id)
    REFERENCES public.parking_customer (id)
    ON DELETE CASCADE,
    CONSTRAINT unq_vehicle_users_customer UNIQUE (vehicle_id, customer_id)

);

