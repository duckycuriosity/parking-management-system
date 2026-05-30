ALTER TABLE public.payment ADD COLUMN if NOT EXISTS pay_type varchar(10) NULL;
ALTER TABLE public.payment ADD COLUMN if NOT EXISTS result_message varchar(500) NULL;
ALTER TABLE public.payment ADD COLUMN if NOT EXISTS result_code varchar(20) NULL;