we need to create plans for 2 different implementations;

right now, nomulus has a console that is registrar centric;

We want to create, or extend the exiting, that is registry centric - registry, not registrar; we'll call it the registry dashboard

We have 2 options:
a) extend nomulus console/ui 
b) create a seperate console/ui

if we go with option a), we need to be able to minimize merge conflicts with the upstream;  we will use the upstream for a lot of updates in teh future, but this registry dashboard is something specific to our business.

initial set of features we believe we need:

* Aggregate Status (total domains)
* Registrars selling our product
* Domains per registrar
* Billing?
* to be able to give registrars free domains
* to be able to give registrars special marketing deals
* built in interactive business analytics; i.e. AI conversation to interact with the data a registry is allowed to access

here is a message from slack that i had with a peer discussing this, that was discussing the possibility of option b:

Glossary of terms:
* registrar = the entity selling actual 2nd level domains for the TLD
registry = the system that runs a TLD, not an actual company;
* RO = registry operator, the entity who 'owns' the TLD, will have agreements with multipel registrar'sthe entity operating the actual registry. Holds the ICANN Registry Agreement (the contract) for the TLD; Is legally and contractually responsible to ICANN for everything — SLAs, policy compliance, escrow, reporting, etc.
* RSP = Registry Service Provider, this is my team; runs the technology layer that supports the TLD for the RO

another definition of these is:

- Registry — The authoritative database and infrastructure (EPP, DNS, WHOIS) that  stores and serves domain registrations for a TLD.                                
- Registry Operator (RO) — The organization that holds the ICANN Registry Agreement and is contractually accountable for a TLD's operation and compliance.            
- Registry Service Provider (RSP) — A technical vendor that builds and runs the    registry infrastructure on behalf of a registry operator.                          
- Registrar — The customer-facing business that sells domain names to registrants and submits transactions to the registry via EPP.                                  
- ICANN — The global authority that sets domain name policy, accredits registrars, and contracts with registry operators to delegate TLDs.                            

you should do research on your own to validate these definitions of different parties involved.


============== START of Message ==============
yeah; We'll have to sort out the UI's schema, and if it's different than nomulus proper;

my thought is the UI (and backend API + mini PG db?) would allow:
A Registry Operator login to have access to 1 or more registrars in teh nomulus data; obviously a registrar has access to 1 or more tld's;

the RO <-> registrar mapping would have to be stored somewhere, i'm leaning to somewhere that is NOT in the nomulus DB;

Whatever data/operations the RO has access to (or operations to perform) would be able to be applied to a single registrar, or all registrars;

the API would utilize the nomulus api where possible, and possibly read data from the nomulus DB (read replica in prod);

so:
      new/mini PG DB
            ^
            |
UI <-> new API layer <-> nomulus API
            |
            v
        nomulus DB


based on what matt describes, the primary use case is going to be price maninpulation for 1 or more registrars; which *should* be able to manipulated via the nomulus API (needs verified)

anyhow, that's kind of what i'm thinking for this; but if we have a UI and/or API templated repo, i'd just start with that;  I know there are opinions on Next vs Nest vs X vs. Y vs. Z; opinion's on API project structure, how UI's should be designed/made, etc. and i havent really touched any of that stuff since all the this new AI stuff has been being cranking

============== END of Message ==============