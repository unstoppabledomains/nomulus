-- local-demo-billing-data.sql
-- Demo data for registry dashboard UI: billing events, domain activity, forecasting
-- Covers 6h, 1d, 7d, 12m (and 24m) time windows with realistic patterns.
--
-- Prerequisites: local-test-data-setup.sql + local-create-domains.sql must run first.
-- Run: PGPASSWORD=test psql -h localhost -p <PORT> -U test -d postgres -f local-demo-billing-data.sql
--
-- What this populates:
--   - BillingEvent            → Registry Revenue tab (Overview + Revenue & Billing)
--   - DomainTransactionRecord → Domain Activity tab
--   - DomainHistory (renew/delete) → Forecasting renewal rates
--   - Domain expiration spread → Forecasting expiration curve

BEGIN;

-- =====================================================================
-- PART 1: BillingEvent
-- Drives: Overview "Registry Revenue", Registry Revenue tab charts
-- Pattern: growing trend (fewer events 12m ago, more recent)
-- Domain/registrar/history mapping:
--   100-MODEM→arsenic/1000, 110-MODEM→mercury/1010, 116-MODEM→thallium/1016
--   122-MODEM→radium/1022, 128-MODEM→polonium/1028, 134-MODEM→antimony/1034
--   102-FLOPPY→arsenic/1002, 112-FLOPPY→mercury/1012, 124-FLOPPY→radium/1024, 132-FLOPPY→cadmium/1032
--   104-PIXEL→arsenic/1004, 118-PIXEL→thallium/1018, 130-PIXEL→polonium/1030
--   106-DIALUP→arsenic/1006, 114-DIALUP→mercury/1014, 120-DIALUP→thallium/1020
--   108-CASSETTE→arsenic/1008, 126-CASSETTE→radium/1026
-- =====================================================================

INSERT INTO "BillingEvent" (
  billing_event_id, registrar_id, domain_history_revision_id, domain_repo_id,
  domain_name, event_time, reason, cost_amount, cost_currency, billing_time, period_years
) VALUES

-- ===================================================================
-- LAST 6 HOURS — visible in 6h, 1d, 7d, 12m ranges
-- ===================================================================
(1,  'arsenic',  1000, '100-MODEM',    'alpha.modem',     NOW()-'1 hour'::interval,   'CREATE',  15.00, 'USD', NOW()-'1 hour'::interval,   1),
(2,  'mercury',  1010, '110-MODEM',    'beta.modem',      NOW()-'2 hours'::interval,  'RENEW',   12.00, 'USD', NOW()-'2 hours'::interval,  1),
(3,  'thallium', 1016, '116-MODEM',    'gamma.modem',     NOW()-'3 hours'::interval,  'CREATE',  15.00, 'USD', NOW()-'3 hours'::interval,  1),
(4,  'arsenic',  1004, '104-PIXEL',    'epsilon.pixel',   NOW()-'1 hour'::interval,   'CREATE',  20.00, 'USD', NOW()-'1 hour'::interval,   1),
(5,  'thallium', 1018, '118-PIXEL',    'nexus.pixel',     NOW()-'4 hours'::interval,  'RENEW',   16.00, 'USD', NOW()-'4 hours'::interval,  1),
(6,  'arsenic',  1002, '102-FLOPPY',   'gamma.floppy',    NOW()-'2 hours'::interval,  'CREATE',  10.00, 'USD', NOW()-'2 hours'::interval,  1),
(7,  'mercury',  1012, '112-FLOPPY',   'delta.floppy',    NOW()-'5 hours'::interval,  'RENEW',    8.00, 'USD', NOW()-'5 hours'::interval,  1),
(8,  'arsenic',  1006, '106-DIALUP',   'quantum.dialup',  NOW()-'3 hours'::interval,  'CREATE',  12.00, 'USD', NOW()-'3 hours'::interval,  1),
(9,  'arsenic',  1008, '108-CASSETTE', 'zenith.cassette', NOW()-'2 hours'::interval,  'CREATE',  18.00, 'USD', NOW()-'2 hours'::interval,  1),
(10, 'radium',   1026, '126-CASSETTE', 'apex.cassette',   NOW()-'5 hours'::interval,  'RENEW',   14.00, 'USD', NOW()-'5 hours'::interval,  1),

-- ===================================================================
-- 6-24 HOURS AGO — visible in 1d, 7d, 12m (NOT 6h)
-- ===================================================================
(11, 'radium',   1022, '122-MODEM',    'alpha.modem',     NOW()-'7 hours'::interval,  'CREATE',  15.00, 'USD', NOW()-'7 hours'::interval,  1),
(12, 'polonium', 1028, '128-MODEM',    'gamma.modem',     NOW()-'10 hours'::interval, 'RENEW',   12.00, 'USD', NOW()-'10 hours'::interval, 1),
(13, 'antimony', 1034, '134-MODEM',    'alpha.modem',     NOW()-'14 hours'::interval, 'CREATE',  15.00, 'USD', NOW()-'14 hours'::interval, 1),
(14, 'arsenic',  1000, '100-MODEM',    'alpha.modem',     NOW()-'20 hours'::interval, 'RENEW',   12.00, 'USD', NOW()-'20 hours'::interval, 1),
(15, 'polonium', 1030, '130-PIXEL',    'nexus.pixel',     NOW()-'8 hours'::interval,  'CREATE',  20.00, 'USD', NOW()-'8 hours'::interval,  1),
(16, 'arsenic',  1004, '104-PIXEL',    'epsilon.pixel',   NOW()-'18 hours'::interval, 'RENEW',   16.00, 'USD', NOW()-'18 hours'::interval, 1),
(17, 'cadmium',  1032, '132-FLOPPY',   'delta.floppy',    NOW()-'9 hours'::interval,  'CREATE',  10.00, 'USD', NOW()-'9 hours'::interval,  1),
(18, 'arsenic',  1002, '102-FLOPPY',   'gamma.floppy',    NOW()-'16 hours'::interval, 'RESTORE', 18.00, 'USD', NOW()-'16 hours'::interval, 1),
(19, 'mercury',  1014, '114-DIALUP',   'quantum.dialup',  NOW()-'11 hours'::interval, 'RENEW',   10.00, 'USD', NOW()-'11 hours'::interval, 1),
(20, 'thallium', 1020, '120-DIALUP',   'stellar.dialup',  NOW()-'22 hours'::interval, 'CREATE',  12.00, 'USD', NOW()-'22 hours'::interval, 1),
(21, 'arsenic',  1008, '108-CASSETTE', 'zenith.cassette', NOW()-'13 hours'::interval, 'RENEW',   14.00, 'USD', NOW()-'13 hours'::interval, 1),

-- ===================================================================
-- 1-7 DAYS AGO — visible in 7d, 12m (NOT 1d or 6h)
-- ===================================================================
-- Day 2
(22, 'arsenic',  1000, '100-MODEM',    'alpha.modem',     NOW()-'2 days'::interval,   'CREATE',  15.00, 'USD', NOW()-'2 days'::interval,   1),
(23, 'mercury',  1010, '110-MODEM',    'beta.modem',      NOW()-'2 days'::interval,   'RENEW',   12.00, 'USD', NOW()-'2 days'::interval,   1),
(24, 'arsenic',  1004, '104-PIXEL',    'epsilon.pixel',   NOW()-'2 days'::interval,   'CREATE',  20.00, 'USD', NOW()-'2 days'::interval,   1),
(25, 'arsenic',  1002, '102-FLOPPY',   'gamma.floppy',    NOW()-'2 days'::interval,   'CREATE',  10.00, 'USD', NOW()-'2 days'::interval,   1),
(26, 'arsenic',  1006, '106-DIALUP',   'quantum.dialup',  NOW()-'2 days'::interval,   'RENEW',   10.00, 'USD', NOW()-'2 days'::interval,   1),
-- Day 3
(27, 'thallium', 1016, '116-MODEM',    'gamma.modem',     NOW()-'3 days'::interval,   'RENEW',   12.00, 'USD', NOW()-'3 days'::interval,   1),
(28, 'polonium', 1030, '130-PIXEL',    'nexus.pixel',     NOW()-'3 days'::interval,   'RENEW',   16.00, 'USD', NOW()-'3 days'::interval,   1),
(29, 'radium',   1024, '124-FLOPPY',   'delta.floppy',    NOW()-'3 days'::interval,   'CREATE',  10.00, 'USD', NOW()-'3 days'::interval,   1),
(30, 'arsenic',  1008, '108-CASSETTE', 'zenith.cassette', NOW()-'3 days'::interval,   'RESTORE', 30.00, 'USD', NOW()-'3 days'::interval,   1),
-- Day 4
(31, 'radium',   1022, '122-MODEM',    'beta.modem',      NOW()-'4 days'::interval,   'CREATE',  15.00, 'USD', NOW()-'4 days'::interval,   1),
(32, 'arsenic',  1004, '104-PIXEL',    'epsilon.pixel',   NOW()-'4 days'::interval,   'RENEW',   16.00, 'USD', NOW()-'4 days'::interval,   1),
(33, 'mercury',  1012, '112-FLOPPY',   'gamma.floppy',    NOW()-'4 days'::interval,   'RENEW',    8.00, 'USD', NOW()-'4 days'::interval,   1),
(34, 'mercury',  1014, '114-DIALUP',   'quantum.dialup',  NOW()-'4 days'::interval,   'CREATE',  12.00, 'USD', NOW()-'4 days'::interval,   1),
-- Day 5
(35, 'polonium', 1028, '128-MODEM',    'alpha.modem',     NOW()-'5 days'::interval,   'CREATE',  15.00, 'USD', NOW()-'5 days'::interval,   1),
(36, 'thallium', 1018, '118-PIXEL',    'nexus.pixel',     NOW()-'5 days'::interval,   'CREATE',  20.00, 'USD', NOW()-'5 days'::interval,   1),
(37, 'cadmium',  1032, '132-FLOPPY',   'delta.floppy',    NOW()-'5 days'::interval,   'CREATE',  10.00, 'USD', NOW()-'5 days'::interval,   1),
(38, 'thallium', 1020, '120-DIALUP',   'stellar.dialup',  NOW()-'5 days'::interval,   'RENEW',   10.00, 'USD', NOW()-'5 days'::interval,   1),
(39, 'radium',   1026, '126-CASSETTE', 'apex.cassette',   NOW()-'5 days'::interval,   'CREATE',  18.00, 'USD', NOW()-'5 days'::interval,   1),
-- Day 6-7
(40, 'antimony', 1034, '134-MODEM',    'gamma.modem',     NOW()-'6 days'::interval,   'RENEW',   12.00, 'USD', NOW()-'6 days'::interval,   1),
(41, 'arsenic',  1000, '100-MODEM',    'alpha.modem',     NOW()-'6 days'::interval,   'CREATE',  15.00, 'USD', NOW()-'6 days'::interval,   1),
(42, 'arsenic',  1002, '102-FLOPPY',   'gamma.floppy',    NOW()-'6 days'::interval,   'RENEW',    8.00, 'USD', NOW()-'6 days'::interval,   1),
(43, 'arsenic',  1006, '106-DIALUP',   'quantum.dialup',  NOW()-'7 days'::interval+'1 hour'::interval, 'CREATE', 12.00, 'USD', NOW()-'7 days'::interval+'1 hour'::interval, 1),
(44, 'arsenic',  1008, '108-CASSETTE', 'zenith.cassette', NOW()-'7 days'::interval+'2 hours'::interval,'RENEW',  14.00, 'USD', NOW()-'7 days'::interval+'2 hours'::interval,1),

-- ===================================================================
-- MONTHLY DATA — last 12 months (growing trend: more recent = more events)
-- ===================================================================

-- Month 1 ago (~8 events)
(50, 'arsenic',  1000, '100-MODEM',    'alpha.modem',     NOW()-'1 month'::interval,                   'CREATE', 15.00, 'USD', NOW()-'1 month'::interval,                   1),
(51, 'mercury',  1010, '110-MODEM',    'beta.modem',      NOW()-'1 month'::interval-'5 days'::interval,'RENEW',  12.00, 'USD', NOW()-'1 month'::interval-'5 days'::interval, 1),
(52, 'arsenic',  1004, '104-PIXEL',    'epsilon.pixel',   NOW()-'1 month'::interval,                   'CREATE', 20.00, 'USD', NOW()-'1 month'::interval,                   1),
(53, 'thallium', 1018, '118-PIXEL',    'nexus.pixel',     NOW()-'1 month'::interval-'3 days'::interval,'RENEW',  16.00, 'USD', NOW()-'1 month'::interval-'3 days'::interval, 1),
(54, 'arsenic',  1002, '102-FLOPPY',   'gamma.floppy',    NOW()-'1 month'::interval,                   'CREATE', 10.00, 'USD', NOW()-'1 month'::interval,                   1),
(55, 'arsenic',  1006, '106-DIALUP',   'quantum.dialup',  NOW()-'1 month'::interval,                   'CREATE', 12.00, 'USD', NOW()-'1 month'::interval,                   1),
(56, 'arsenic',  1008, '108-CASSETTE', 'zenith.cassette', NOW()-'1 month'::interval,                   'RENEW',  14.00, 'USD', NOW()-'1 month'::interval,                   1),
(57, 'polonium', 1028, '128-MODEM',    'gamma.modem',     NOW()-'1 month'::interval-'12 days'::interval,'RESTORE',25.00,'USD',NOW()-'1 month'::interval-'12 days'::interval, 1),

-- Month 2 ago (~9 events)
(60, 'arsenic',  1000, '100-MODEM',    'alpha.modem',     NOW()-'2 months'::interval,                   'CREATE', 15.00, 'USD', NOW()-'2 months'::interval,                   1),
(61, 'mercury',  1010, '110-MODEM',    'beta.modem',      NOW()-'2 months'::interval,                   'RENEW',  12.00, 'USD', NOW()-'2 months'::interval,                   1),
(62, 'thallium', 1016, '116-MODEM',    'gamma.modem',     NOW()-'2 months'::interval-'7 days'::interval,'CREATE', 15.00, 'USD', NOW()-'2 months'::interval-'7 days'::interval, 1),
(63, 'arsenic',  1004, '104-PIXEL',    'epsilon.pixel',   NOW()-'2 months'::interval,                   'RENEW',  16.00, 'USD', NOW()-'2 months'::interval,                   1),
(64, 'arsenic',  1002, '102-FLOPPY',   'gamma.floppy',    NOW()-'2 months'::interval,                   'RENEW',   8.00, 'USD', NOW()-'2 months'::interval,                   1),
(65, 'mercury',  1014, '114-DIALUP',   'quantum.dialup',  NOW()-'2 months'::interval,                   'CREATE', 12.00, 'USD', NOW()-'2 months'::interval,                   1),
(66, 'radium',   1026, '126-CASSETTE', 'apex.cassette',   NOW()-'2 months'::interval,                   'CREATE', 18.00, 'USD', NOW()-'2 months'::interval,                   1),
(67, 'polonium', 1030, '130-PIXEL',    'nexus.pixel',     NOW()-'2 months'::interval-'5 days'::interval,'CREATE', 20.00, 'USD', NOW()-'2 months'::interval-'5 days'::interval, 1),
(68, 'antimony', 1034, '134-MODEM',    'beta.modem',      NOW()-'2 months'::interval-'18 days'::interval,'CREATE',15.00,'USD',NOW()-'2 months'::interval-'18 days'::interval, 1),

-- Month 3 ago (~10 events)
(70, 'arsenic',  1000, '100-MODEM',    'alpha.modem',     NOW()-'3 months'::interval,                   'RENEW',  12.00, 'USD', NOW()-'3 months'::interval,                   1),
(71, 'arsenic',  1000, '100-MODEM',    'alpha.modem',     NOW()-'3 months'::interval-'5 days'::interval,'CREATE', 15.00, 'USD', NOW()-'3 months'::interval-'5 days'::interval, 1),
(72, 'thallium', 1018, '118-PIXEL',    'nexus.pixel',     NOW()-'3 months'::interval,                   'CREATE', 20.00, 'USD', NOW()-'3 months'::interval,                   1),
(73, 'cadmium',  1032, '132-FLOPPY',   'delta.floppy',    NOW()-'3 months'::interval,                   'CREATE', 10.00, 'USD', NOW()-'3 months'::interval,                   1),
(74, 'arsenic',  1002, '102-FLOPPY',   'gamma.floppy',    NOW()-'3 months'::interval-'8 days'::interval,'RESTORE',18.00, 'USD', NOW()-'3 months'::interval-'8 days'::interval, 1),
(75, 'thallium', 1020, '120-DIALUP',   'stellar.dialup',  NOW()-'3 months'::interval,                   'RENEW',  10.00, 'USD', NOW()-'3 months'::interval,                   1),
(76, 'arsenic',  1008, '108-CASSETTE', 'zenith.cassette', NOW()-'3 months'::interval,                   'CREATE', 18.00, 'USD', NOW()-'3 months'::interval,                   1),
(77, 'polonium', 1028, '128-MODEM',    'gamma.modem',     NOW()-'3 months'::interval-'14 days'::interval,'RENEW', 12.00, 'USD', NOW()-'3 months'::interval-'14 days'::interval,1),
(78, 'arsenic',  1004, '104-PIXEL',    'epsilon.pixel',   NOW()-'3 months'::interval-'20 days'::interval,'CREATE',20.00, 'USD', NOW()-'3 months'::interval-'20 days'::interval,1),
(79, 'mercury',  1010, '110-MODEM',    'beta.modem',      NOW()-'3 months'::interval-'25 days'::interval,'CREATE',15.00, 'USD', NOW()-'3 months'::interval-'25 days'::interval,1),

-- Month 4 ago (~9 events)
(80, 'arsenic',  1000, '100-MODEM',    'alpha.modem',     NOW()-'4 months'::interval,                   'CREATE', 15.00, 'USD', NOW()-'4 months'::interval,                   1),
(81, 'mercury',  1010, '110-MODEM',    'beta.modem',      NOW()-'4 months'::interval,                   'RENEW',  12.00, 'USD', NOW()-'4 months'::interval,                   1),
(82, 'radium',   1022, '122-MODEM',    'alpha.modem',     NOW()-'4 months'::interval-'5 days'::interval,'CREATE', 15.00, 'USD', NOW()-'4 months'::interval-'5 days'::interval, 1),
(83, 'arsenic',  1004, '104-PIXEL',    'epsilon.pixel',   NOW()-'4 months'::interval,                   'CREATE', 20.00, 'USD', NOW()-'4 months'::interval,                   1),
(84, 'arsenic',  1002, '102-FLOPPY',   'gamma.floppy',    NOW()-'4 months'::interval,                   'CREATE', 10.00, 'USD', NOW()-'4 months'::interval,                   1),
(85, 'radium',   1024, '124-FLOPPY',   'delta.floppy',    NOW()-'4 months'::interval,                   'RENEW',   8.00, 'USD', NOW()-'4 months'::interval,                   1),
(86, 'arsenic',  1006, '106-DIALUP',   'quantum.dialup',  NOW()-'4 months'::interval,                   'RENEW',  10.00, 'USD', NOW()-'4 months'::interval,                   1),
(87, 'arsenic',  1008, '108-CASSETTE', 'zenith.cassette', NOW()-'4 months'::interval,                   'CREATE', 18.00, 'USD', NOW()-'4 months'::interval,                   1),
(88, 'thallium', 1016, '116-MODEM',    'gamma.modem',     NOW()-'4 months'::interval-'10 days'::interval,'RESTORE',25.00,'USD',NOW()-'4 months'::interval-'10 days'::interval, 1),

-- Month 5 ago (~7 events)
(90, 'arsenic',  1000, '100-MODEM',    'alpha.modem',     NOW()-'5 months'::interval, 'CREATE', 15.00, 'USD', NOW()-'5 months'::interval, 1),
(91, 'arsenic',  1004, '104-PIXEL',    'epsilon.pixel',   NOW()-'5 months'::interval, 'CREATE', 20.00, 'USD', NOW()-'5 months'::interval, 1),
(92, 'arsenic',  1002, '102-FLOPPY',   'gamma.floppy',    NOW()-'5 months'::interval, 'RENEW',   8.00, 'USD', NOW()-'5 months'::interval, 1),
(93, 'mercury',  1010, '110-MODEM',    'beta.modem',      NOW()-'5 months'::interval, 'RENEW',  12.00, 'USD', NOW()-'5 months'::interval, 1),
(94, 'thallium', 1018, '118-PIXEL',    'nexus.pixel',     NOW()-'5 months'::interval, 'RENEW',  16.00, 'USD', NOW()-'5 months'::interval, 1),
(95, 'radium',   1026, '126-CASSETTE', 'apex.cassette',   NOW()-'5 months'::interval, 'RENEW',  14.00, 'USD', NOW()-'5 months'::interval, 1),
(96, 'mercury',  1014, '114-DIALUP',   'quantum.dialup',  NOW()-'5 months'::interval, 'CREATE', 12.00, 'USD', NOW()-'5 months'::interval, 1),

-- Month 6 ago (~7 events)
(100, 'arsenic',  1000, '100-MODEM',    'alpha.modem',     NOW()-'6 months'::interval, 'CREATE', 15.00, 'USD', NOW()-'6 months'::interval, 1),
(101, 'mercury',  1010, '110-MODEM',    'beta.modem',      NOW()-'6 months'::interval, 'RENEW',  12.00, 'USD', NOW()-'6 months'::interval, 1),
(102, 'thallium', 1016, '116-MODEM',    'gamma.modem',     NOW()-'6 months'::interval, 'CREATE', 15.00, 'USD', NOW()-'6 months'::interval, 1),
(103, 'arsenic',  1004, '104-PIXEL',    'epsilon.pixel',   NOW()-'6 months'::interval, 'RENEW',  16.00, 'USD', NOW()-'6 months'::interval, 1),
(104, 'arsenic',  1002, '102-FLOPPY',   'gamma.floppy',    NOW()-'6 months'::interval, 'CREATE', 10.00, 'USD', NOW()-'6 months'::interval, 1),
(105, 'arsenic',  1006, '106-DIALUP',   'quantum.dialup',  NOW()-'6 months'::interval, 'CREATE', 12.00, 'USD', NOW()-'6 months'::interval, 1),
(106, 'arsenic',  1008, '108-CASSETTE', 'zenith.cassette', NOW()-'6 months'::interval, 'CREATE', 18.00, 'USD', NOW()-'6 months'::interval, 1),

-- Month 7 ago (~5 events)
(110, 'arsenic',  1000, '100-MODEM',    'alpha.modem',     NOW()-'7 months'::interval, 'CREATE', 15.00, 'USD', NOW()-'7 months'::interval, 1),
(111, 'mercury',  1010, '110-MODEM',    'beta.modem',      NOW()-'7 months'::interval, 'RENEW',  12.00, 'USD', NOW()-'7 months'::interval, 1),
(112, 'arsenic',  1004, '104-PIXEL',    'epsilon.pixel',   NOW()-'7 months'::interval, 'CREATE', 20.00, 'USD', NOW()-'7 months'::interval, 1),
(113, 'cadmium',  1032, '132-FLOPPY',   'delta.floppy',    NOW()-'7 months'::interval, 'CREATE', 10.00, 'USD', NOW()-'7 months'::interval, 1),
(114, 'thallium', 1020, '120-DIALUP',   'stellar.dialup',  NOW()-'7 months'::interval, 'RENEW',  10.00, 'USD', NOW()-'7 months'::interval, 1),

-- Month 8 ago (~5 events)
(120, 'arsenic',  1000, '100-MODEM',    'alpha.modem',     NOW()-'8 months'::interval, 'RENEW',  12.00, 'USD', NOW()-'8 months'::interval, 1),
(121, 'thallium', 1016, '116-MODEM',    'gamma.modem',     NOW()-'8 months'::interval, 'CREATE', 15.00, 'USD', NOW()-'8 months'::interval, 1),
(122, 'polonium', 1030, '130-PIXEL',    'nexus.pixel',     NOW()-'8 months'::interval, 'CREATE', 20.00, 'USD', NOW()-'8 months'::interval, 1),
(123, 'arsenic',  1002, '102-FLOPPY',   'gamma.floppy',    NOW()-'8 months'::interval, 'RENEW',   8.00, 'USD', NOW()-'8 months'::interval, 1),
(124, 'arsenic',  1008, '108-CASSETTE', 'zenith.cassette', NOW()-'8 months'::interval, 'RENEW',  14.00, 'USD', NOW()-'8 months'::interval, 1),

-- Month 9 ago (~4 events)
(130, 'arsenic',  1000, '100-MODEM',    'alpha.modem',     NOW()-'9 months'::interval, 'CREATE', 15.00, 'USD', NOW()-'9 months'::interval, 1),
(131, 'arsenic',  1004, '104-PIXEL',    'epsilon.pixel',   NOW()-'9 months'::interval, 'RENEW',  16.00, 'USD', NOW()-'9 months'::interval, 1),
(132, 'mercury',  1012, '112-FLOPPY',   'gamma.floppy',    NOW()-'9 months'::interval, 'CREATE', 10.00, 'USD', NOW()-'9 months'::interval, 1),
(133, 'arsenic',  1006, '106-DIALUP',   'quantum.dialup',  NOW()-'9 months'::interval, 'RENEW',  10.00, 'USD', NOW()-'9 months'::interval, 1),

-- Month 10 ago (~4 events)
(140, 'arsenic',  1000, '100-MODEM',    'alpha.modem',     NOW()-'10 months'::interval, 'RENEW',  12.00, 'USD', NOW()-'10 months'::interval, 1),
(141, 'radium',   1022, '122-MODEM',    'alpha.modem',     NOW()-'10 months'::interval, 'CREATE', 15.00, 'USD', NOW()-'10 months'::interval, 1),
(142, 'arsenic',  1004, '104-PIXEL',    'epsilon.pixel',   NOW()-'10 months'::interval, 'CREATE', 20.00, 'USD', NOW()-'10 months'::interval, 1),
(143, 'arsenic',  1002, '102-FLOPPY',   'gamma.floppy',    NOW()-'10 months'::interval, 'RENEW',   8.00, 'USD', NOW()-'10 months'::interval, 1),

-- Month 11 ago (~4 events)
(150, 'arsenic',  1000, '100-MODEM',    'alpha.modem',     NOW()-'11 months'::interval, 'CREATE', 15.00, 'USD', NOW()-'11 months'::interval, 1),
(151, 'mercury',  1010, '110-MODEM',    'beta.modem',      NOW()-'11 months'::interval, 'RENEW',  12.00, 'USD', NOW()-'11 months'::interval, 1),
(152, 'arsenic',  1004, '104-PIXEL',    'epsilon.pixel',   NOW()-'11 months'::interval, 'RENEW',  16.00, 'USD', NOW()-'11 months'::interval, 1),
(153, 'arsenic',  1008, '108-CASSETTE', 'zenith.cassette', NOW()-'11 months'::interval, 'CREATE', 18.00, 'USD', NOW()-'11 months'::interval, 1),

-- Month 12 ago (~3 events — oldest, smallest batch)
(160, 'arsenic',  1000, '100-MODEM',    'alpha.modem',     NOW()-'12 months'::interval+'5 days'::interval, 'CREATE', 15.00, 'USD', NOW()-'12 months'::interval+'5 days'::interval, 1),
(161, 'arsenic',  1004, '104-PIXEL',    'epsilon.pixel',   NOW()-'12 months'::interval+'3 days'::interval, 'CREATE', 20.00, 'USD', NOW()-'12 months'::interval+'3 days'::interval, 1),
(162, 'arsenic',  1002, '102-FLOPPY',   'gamma.floppy',    NOW()-'12 months'::interval+'8 days'::interval, 'RENEW',   8.00, 'USD', NOW()-'12 months'::interval+'8 days'::interval, 1);

-- =====================================================================
-- PART 2: DomainTransactionRecord
-- Drives: Domain Activity tab (CREATES, RENEWS, DELETES, RESTORES)
-- Fields: report_amount (int), report_field (text), reporting_time, tld
-- report_field patterns:
--   NET_ADDS_1_YR → CREATES
--   NET_RENEWS_1_YR → RENEWS
--   DELETED_DOMAINS_GRACE → DELETES
--   RESTORED_DOMAINS → RESTORES
--   TRANSFER_SUCCESSFUL → TRANSFERS
-- =====================================================================

INSERT INTO "DomainTransactionRecord" (
  report_amount, report_field, reporting_time, tld
) VALUES

-- === LAST 6 HOURS ===
(3, 'NET_ADDS_1_YR',    NOW()-'1 hour'::interval,  'modem'),
(2, 'NET_RENEWS_1_YR',  NOW()-'2 hours'::interval, 'modem'),
(1, 'NET_ADDS_1_YR',    NOW()-'1 hour'::interval,  'pixel'),
(1, 'NET_RENEWS_1_YR',  NOW()-'3 hours'::interval, 'pixel'),
(2, 'NET_ADDS_1_YR',    NOW()-'2 hours'::interval, 'floppy'),
(1, 'NET_ADDS_1_YR',    NOW()-'3 hours'::interval, 'dialup'),
(1, 'NET_ADDS_1_YR',    NOW()-'2 hours'::interval, 'cassette'),

-- === 6-24 HOURS AGO ===
(4, 'NET_ADDS_1_YR',    NOW()-'8 hours'::interval,  'modem'),
(3, 'NET_RENEWS_1_YR',  NOW()-'14 hours'::interval, 'modem'),
(1, 'RESTORED_DOMAINS', NOW()-'16 hours'::interval, 'floppy'),
(2, 'NET_ADDS_1_YR',    NOW()-'9 hours'::interval,  'pixel'),
(1, 'NET_RENEWS_1_YR',  NOW()-'18 hours'::interval, 'pixel'),
(2, 'NET_ADDS_1_YR',    NOW()-'11 hours'::interval, 'floppy'),
(1, 'NET_RENEWS_1_YR',  NOW()-'22 hours'::interval, 'dialup'),
(1, 'NET_ADDS_1_YR',    NOW()-'12 hours'::interval, 'cassette'),

-- === DAILY DATA (days 2-7) ===
(5, 'NET_ADDS_1_YR',    NOW()-'2 days'::interval,  'modem'),
(4, 'NET_RENEWS_1_YR',  NOW()-'2 days'::interval,  'modem'),
(2, 'NET_ADDS_1_YR',    NOW()-'2 days'::interval,  'pixel'),
(2, 'NET_ADDS_1_YR',    NOW()-'2 days'::interval,  'floppy'),
(1, 'NET_RENEWS_1_YR',  NOW()-'2 days'::interval,  'dialup'),
(1, 'DELETED_DOMAINS_GRACE', NOW()-'2 days'::interval, 'modem'),

(6, 'NET_ADDS_1_YR',    NOW()-'3 days'::interval,  'modem'),
(3, 'NET_RENEWS_1_YR',  NOW()-'3 days'::interval,  'modem'),
(1, 'NET_ADDS_1_YR',    NOW()-'3 days'::interval,  'pixel'),
(1, 'RESTORED_DOMAINS', NOW()-'3 days'::interval,  'cassette'),
(1, 'NET_ADDS_1_YR',    NOW()-'3 days'::interval,  'floppy'),

(4, 'NET_ADDS_1_YR',    NOW()-'4 days'::interval,  'modem'),
(5, 'NET_RENEWS_1_YR',  NOW()-'4 days'::interval,  'modem'),
(2, 'NET_RENEWS_1_YR',  NOW()-'4 days'::interval,  'pixel'),
(2, 'NET_RENEWS_1_YR',  NOW()-'4 days'::interval,  'floppy'),
(1, 'NET_ADDS_1_YR',    NOW()-'4 days'::interval,  'dialup'),

(7, 'NET_ADDS_1_YR',    NOW()-'5 days'::interval,  'modem'),
(2, 'NET_RENEWS_1_YR',  NOW()-'5 days'::interval,  'modem'),
(2, 'NET_ADDS_1_YR',    NOW()-'5 days'::interval,  'pixel'),
(1, 'NET_ADDS_1_YR',    NOW()-'5 days'::interval,  'floppy'),
(1, 'NET_ADDS_1_YR',    NOW()-'5 days'::interval,  'cassette'),
(1, 'DELETED_DOMAINS_GRACE', NOW()-'5 days'::interval, 'pixel'),

(5, 'NET_ADDS_1_YR',    NOW()-'6 days'::interval,  'modem'),
(4, 'NET_RENEWS_1_YR',  NOW()-'6 days'::interval,  'modem'),
(1, 'NET_ADDS_1_YR',    NOW()-'6 days'::interval,  'floppy'),
(1, 'NET_RENEWS_1_YR',  NOW()-'7 days'::interval+'1 hour'::interval, 'dialup'),

-- === MONTHLY DATA (last 12 months) ===
-- Month 1
(12, 'NET_ADDS_1_YR',   NOW()-'1 month'::interval,  'modem'),
(10, 'NET_RENEWS_1_YR', NOW()-'1 month'::interval,  'modem'),
(5,  'NET_ADDS_1_YR',   NOW()-'1 month'::interval,  'pixel'),
(4,  'NET_RENEWS_1_YR', NOW()-'1 month'::interval,  'pixel'),
(6,  'NET_ADDS_1_YR',   NOW()-'1 month'::interval,  'floppy'),
(4,  'NET_RENEWS_1_YR', NOW()-'1 month'::interval,  'floppy'),
(3,  'NET_ADDS_1_YR',   NOW()-'1 month'::interval,  'dialup'),
(3,  'NET_ADDS_1_YR',   NOW()-'1 month'::interval,  'cassette'),
(2,  'DELETED_DOMAINS_GRACE', NOW()-'1 month'::interval, 'modem'),
(1,  'RESTORED_DOMAINS',NOW()-'1 month'::interval,  'modem'),

-- Month 2
(11, 'NET_ADDS_1_YR',   NOW()-'2 months'::interval, 'modem'),
(9,  'NET_RENEWS_1_YR', NOW()-'2 months'::interval, 'modem'),
(5,  'NET_ADDS_1_YR',   NOW()-'2 months'::interval, 'pixel'),
(3,  'NET_RENEWS_1_YR', NOW()-'2 months'::interval, 'pixel'),
(5,  'NET_ADDS_1_YR',   NOW()-'2 months'::interval, 'floppy'),
(2,  'NET_RENEWS_1_YR', NOW()-'2 months'::interval, 'floppy'),
(3,  'NET_ADDS_1_YR',   NOW()-'2 months'::interval, 'dialup'),
(2,  'NET_ADDS_1_YR',   NOW()-'2 months'::interval, 'cassette'),
(2,  'DELETED_DOMAINS_GRACE', NOW()-'2 months'::interval, 'modem'),

-- Month 3
(10, 'NET_ADDS_1_YR',   NOW()-'3 months'::interval, 'modem'),
(8,  'NET_RENEWS_1_YR', NOW()-'3 months'::interval, 'modem'),
(4,  'NET_ADDS_1_YR',   NOW()-'3 months'::interval, 'pixel'),
(4,  'NET_RENEWS_1_YR', NOW()-'3 months'::interval, 'pixel'),
(4,  'NET_ADDS_1_YR',   NOW()-'3 months'::interval, 'floppy'),
(1,  'RESTORED_DOMAINS',NOW()-'3 months'::interval, 'floppy'),
(2,  'NET_ADDS_1_YR',   NOW()-'3 months'::interval, 'dialup'),
(2,  'NET_ADDS_1_YR',   NOW()-'3 months'::interval, 'cassette'),
(1,  'DELETED_DOMAINS_GRACE', NOW()-'3 months'::interval, 'pixel'),

-- Month 4
(9,  'NET_ADDS_1_YR',   NOW()-'4 months'::interval, 'modem'),
(8,  'NET_RENEWS_1_YR', NOW()-'4 months'::interval, 'modem'),
(4,  'NET_ADDS_1_YR',   NOW()-'4 months'::interval, 'pixel'),
(3,  'NET_RENEWS_1_YR', NOW()-'4 months'::interval, 'pixel'),
(4,  'NET_ADDS_1_YR',   NOW()-'4 months'::interval, 'floppy'),
(2,  'NET_RENEWS_1_YR', NOW()-'4 months'::interval, 'floppy'),
(2,  'NET_ADDS_1_YR',   NOW()-'4 months'::interval, 'dialup'),
(2,  'NET_ADDS_1_YR',   NOW()-'4 months'::interval, 'cassette'),

-- Months 5-12 (shorter, decreasing trend)
(8,  'NET_ADDS_1_YR',   NOW()-'5 months'::interval, 'modem'),
(7,  'NET_RENEWS_1_YR', NOW()-'5 months'::interval, 'modem'),
(3,  'NET_ADDS_1_YR',   NOW()-'5 months'::interval, 'pixel'),
(3,  'NET_ADDS_1_YR',   NOW()-'5 months'::interval, 'floppy'),
(2,  'NET_RENEWS_1_YR', NOW()-'5 months'::interval, 'floppy'),
(2,  'NET_ADDS_1_YR',   NOW()-'5 months'::interval, 'dialup'),

(7,  'NET_ADDS_1_YR',   NOW()-'6 months'::interval, 'modem'),
(6,  'NET_RENEWS_1_YR', NOW()-'6 months'::interval, 'modem'),
(3,  'NET_ADDS_1_YR',   NOW()-'6 months'::interval, 'pixel'),
(2,  'NET_ADDS_1_YR',   NOW()-'6 months'::interval, 'floppy'),
(2,  'NET_ADDS_1_YR',   NOW()-'6 months'::interval, 'dialup'),
(1,  'NET_ADDS_1_YR',   NOW()-'6 months'::interval, 'cassette'),

(6,  'NET_ADDS_1_YR',   NOW()-'7 months'::interval, 'modem'),
(5,  'NET_RENEWS_1_YR', NOW()-'7 months'::interval, 'modem'),
(2,  'NET_ADDS_1_YR',   NOW()-'7 months'::interval, 'pixel'),
(2,  'NET_ADDS_1_YR',   NOW()-'7 months'::interval, 'floppy'),
(1,  'DELETED_DOMAINS_GRACE', NOW()-'7 months'::interval, 'modem'),

(5,  'NET_ADDS_1_YR',   NOW()-'8 months'::interval, 'modem'),
(5,  'NET_RENEWS_1_YR', NOW()-'8 months'::interval, 'modem'),
(2,  'NET_ADDS_1_YR',   NOW()-'8 months'::interval, 'pixel'),
(2,  'NET_ADDS_1_YR',   NOW()-'8 months'::interval, 'floppy'),

(5,  'NET_ADDS_1_YR',   NOW()-'9 months'::interval, 'modem'),
(4,  'NET_RENEWS_1_YR', NOW()-'9 months'::interval, 'modem'),
(2,  'NET_ADDS_1_YR',   NOW()-'9 months'::interval, 'pixel'),
(1,  'NET_ADDS_1_YR',   NOW()-'9 months'::interval, 'floppy'),

(4,  'NET_ADDS_1_YR',   NOW()-'10 months'::interval, 'modem'),
(4,  'NET_RENEWS_1_YR', NOW()-'10 months'::interval, 'modem'),
(2,  'NET_ADDS_1_YR',   NOW()-'10 months'::interval, 'pixel'),
(1,  'NET_ADDS_1_YR',   NOW()-'10 months'::interval, 'floppy'),

(4,  'NET_ADDS_1_YR',   NOW()-'11 months'::interval, 'modem'),
(3,  'NET_RENEWS_1_YR', NOW()-'11 months'::interval, 'modem'),
(2,  'NET_ADDS_1_YR',   NOW()-'11 months'::interval, 'pixel'),
(1,  'NET_ADDS_1_YR',   NOW()-'11 months'::interval, 'floppy'),

(3,  'NET_ADDS_1_YR',   NOW()-'12 months'::interval+'5 days'::interval, 'modem'),
(3,  'NET_RENEWS_1_YR', NOW()-'12 months'::interval+'5 days'::interval, 'modem'),
(1,  'NET_ADDS_1_YR',   NOW()-'12 months'::interval+'5 days'::interval, 'pixel');

-- =====================================================================
-- PART 3: DomainHistory — RENEW / DELETE entries for forecasting renewal rates
-- Drives: Forecasting tab "Renewal Rate by TLD"
-- Uses revision IDs 2000+ (new entries, separate from DOMAIN_CREATE at 1000-1035)
-- =====================================================================

INSERT INTO "DomainHistory" (
  history_revision_id, history_by_superuser, history_modification_time,
  history_type, domain_repo_id
) VALUES
-- modem — high renewal rate (10 renews, 1 delete)
(2000, false, NOW()-'1 month'::interval,  'DOMAIN_RENEW',      '100-MODEM'),
(2001, false, NOW()-'2 months'::interval, 'DOMAIN_RENEW',      '110-MODEM'),
(2002, false, NOW()-'3 months'::interval, 'DOMAIN_AUTORENEW',  '116-MODEM'),
(2003, false, NOW()-'4 months'::interval, 'DOMAIN_RENEW',      '122-MODEM'),
(2004, false, NOW()-'5 months'::interval, 'DOMAIN_AUTORENEW',  '128-MODEM'),
(2005, false, NOW()-'6 months'::interval, 'DOMAIN_RENEW',      '134-MODEM'),
(2006, false, NOW()-'7 months'::interval, 'DOMAIN_AUTORENEW',  '100-MODEM'),
(2007, false, NOW()-'8 months'::interval, 'DOMAIN_RENEW',      '110-MODEM'),
(2008, false, NOW()-'9 months'::interval, 'DOMAIN_RENEW',      '116-MODEM'),
(2009, false, NOW()-'10 months'::interval,'DOMAIN_AUTORENEW',  '122-MODEM'),
(2010, false, NOW()-'8 months'::interval, 'DOMAIN_DELETE',     '128-MODEM'),

-- pixel — very high renewal rate (8 renews, 1 delete)
(2020, false, NOW()-'1 month'::interval,  'DOMAIN_RENEW',      '104-PIXEL'),
(2021, false, NOW()-'2 months'::interval, 'DOMAIN_AUTORENEW',  '118-PIXEL'),
(2022, false, NOW()-'3 months'::interval, 'DOMAIN_RENEW',      '130-PIXEL'),
(2023, false, NOW()-'4 months'::interval, 'DOMAIN_AUTORENEW',  '104-PIXEL'),
(2024, false, NOW()-'5 months'::interval, 'DOMAIN_RENEW',      '118-PIXEL'),
(2025, false, NOW()-'6 months'::interval, 'DOMAIN_RENEW',      '130-PIXEL'),
(2026, false, NOW()-'8 months'::interval, 'DOMAIN_AUTORENEW',  '104-PIXEL'),
(2027, false, NOW()-'10 months'::interval,'DOMAIN_RENEW',      '118-PIXEL'),
(2028, false, NOW()-'9 months'::interval, 'DOMAIN_DELETE',     '130-PIXEL'),

-- floppy — moderate renewal rate (6 renews, 2 deletes)
(2030, false, NOW()-'1 month'::interval,  'DOMAIN_RENEW',      '102-FLOPPY'),
(2031, false, NOW()-'3 months'::interval, 'DOMAIN_AUTORENEW',  '112-FLOPPY'),
(2032, false, NOW()-'5 months'::interval, 'DOMAIN_RENEW',      '124-FLOPPY'),
(2033, false, NOW()-'7 months'::interval, 'DOMAIN_AUTORENEW',  '132-FLOPPY'),
(2034, false, NOW()-'8 months'::interval, 'DOMAIN_RENEW',      '102-FLOPPY'),
(2035, false, NOW()-'10 months'::interval,'DOMAIN_RENEW',      '112-FLOPPY'),
(2036, false, NOW()-'4 months'::interval, 'DOMAIN_DELETE',     '124-FLOPPY'),
(2037, false, NOW()-'9 months'::interval, 'DOMAIN_DELETE',     '132-FLOPPY'),

-- dialup — lower renewal rate (5 renews, 2 deletes)
(2040, false, NOW()-'1 month'::interval,  'DOMAIN_RENEW',      '106-DIALUP'),
(2041, false, NOW()-'3 months'::interval, 'DOMAIN_AUTORENEW',  '114-DIALUP'),
(2042, false, NOW()-'5 months'::interval, 'DOMAIN_RENEW',      '120-DIALUP'),
(2043, false, NOW()-'7 months'::interval, 'DOMAIN_RENEW',      '106-DIALUP'),
(2044, false, NOW()-'9 months'::interval, 'DOMAIN_AUTORENEW',  '114-DIALUP'),
(2045, false, NOW()-'4 months'::interval, 'DOMAIN_DELETE',     '120-DIALUP'),
(2046, false, NOW()-'8 months'::interval, 'DOMAIN_DELETE',     '106-DIALUP'),

-- cassette — moderate renewal rate (4 renews, 1 delete)
(2050, false, NOW()-'2 months'::interval, 'DOMAIN_RENEW',      '108-CASSETTE'),
(2051, false, NOW()-'4 months'::interval, 'DOMAIN_AUTORENEW',  '126-CASSETTE'),
(2052, false, NOW()-'6 months'::interval, 'DOMAIN_RENEW',      '108-CASSETTE'),
(2053, false, NOW()-'9 months'::interval, 'DOMAIN_RENEW',      '126-CASSETTE'),
(2054, false, NOW()-'7 months'::interval, 'DOMAIN_DELETE',     '108-CASSETTE');

-- =====================================================================
-- PART 4: Spread domain expiration times for forecasting expiration curve
-- Currently all 36 domains expire 2027-04-02 (12m from now = spike)
-- Spread them across 4-18 months out for a realistic curve shape
-- =====================================================================

-- Expire in ~4-5 months (cassette domains)
UPDATE "Domain" SET registration_expiration_time = NOW() + INTERVAL '4 months'
WHERE repo_id IN ('108-CASSETTE');

UPDATE "Domain" SET registration_expiration_time = NOW() + INTERVAL '5 months'
WHERE repo_id IN ('109-CASSETTE', '126-CASSETTE');

-- Expire in ~6-7 months (dialup + some floppy)
UPDATE "Domain" SET registration_expiration_time = NOW() + INTERVAL '6 months'
WHERE repo_id IN ('106-DIALUP', '107-DIALUP', '127-CASSETTE');

UPDATE "Domain" SET registration_expiration_time = NOW() + INTERVAL '7 months'
WHERE repo_id IN ('114-DIALUP', '115-DIALUP', '132-FLOPPY');

-- Expire in ~8-9 months (floppy + some pixel)
UPDATE "Domain" SET registration_expiration_time = NOW() + INTERVAL '8 months'
WHERE repo_id IN ('102-FLOPPY', '103-FLOPPY', '133-FLOPPY');

UPDATE "Domain" SET registration_expiration_time = NOW() + INTERVAL '9 months'
WHERE repo_id IN ('112-FLOPPY', '113-FLOPPY', '120-DIALUP');

-- Expire in ~10-11 months (modem + pixel)
UPDATE "Domain" SET registration_expiration_time = NOW() + INTERVAL '10 months'
WHERE repo_id IN ('104-PIXEL', '105-PIXEL', '124-FLOPPY');

UPDATE "Domain" SET registration_expiration_time = NOW() + INTERVAL '11 months'
WHERE repo_id IN ('118-PIXEL', '119-PIXEL', '125-FLOPPY', '121-DIALUP');

-- Expire in ~12-13 months (modem — most domains)
UPDATE "Domain" SET registration_expiration_time = NOW() + INTERVAL '12 months'
WHERE repo_id IN ('100-MODEM', '101-MODEM', '110-MODEM', '111-MODEM');

UPDATE "Domain" SET registration_expiration_time = NOW() + INTERVAL '13 months'
WHERE repo_id IN ('116-MODEM', '117-MODEM', '130-PIXEL', '131-PIXEL');

-- Expire in ~14-15 months (remaining modem)
UPDATE "Domain" SET registration_expiration_time = NOW() + INTERVAL '14 months'
WHERE repo_id IN ('122-MODEM', '123-MODEM');

UPDATE "Domain" SET registration_expiration_time = NOW() + INTERVAL '15 months'
WHERE repo_id IN ('128-MODEM', '129-MODEM');

-- Expire in ~16-17 months (antimony/polonium modem)
UPDATE "Domain" SET registration_expiration_time = NOW() + INTERVAL '16 months'
WHERE repo_id IN ('134-MODEM', '135-MODEM');

COMMIT;

-- Verification
SELECT 'BillingEvent rows:' AS info, COUNT(*) AS count FROM "BillingEvent";
SELECT 'DomainTransactionRecord rows:' AS info, COUNT(*) AS count FROM "DomainTransactionRecord";
SELECT 'DomainHistory renew/delete rows:' AS info, COUNT(*) AS count
  FROM "DomainHistory"
  WHERE history_type IN ('DOMAIN_RENEW', 'DOMAIN_AUTORENEW', 'DOMAIN_DELETE')
  AND history_revision_id >= 2000;
SELECT 'Domain expiration spread (months from now):' AS info,
  ROUND(EXTRACT(EPOCH FROM (registration_expiration_time - NOW())) / 2592000) AS months_out,
  COUNT(*) AS domains
FROM "Domain"
WHERE tld IN ('modem','floppy','pixel','dialup','cassette')
GROUP BY months_out
ORDER BY months_out;
