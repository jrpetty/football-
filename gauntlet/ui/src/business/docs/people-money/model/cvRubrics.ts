/**
 * Shortlist the Candidates (work.cv-shortlist): each job's relevant titles, must-have qualifications and evidence
 * bullets, copied from the generator (verification/realworld-business/cv.mjs, JOBS) so the close-up can tick every
 * requirement per CV. The close-up re-applies the published rubric with these and shows the ticks only when its
 * shortlist and scores equal the answer key exactly (test/people-money-docs.test.ts checks every case).
 */
export interface CvJobRubric {
  relevant: string[];
  mustOk: string[];
  degreesOk: string[];
  bonus: string;
  nice: Array<{ id: string; label: string; pts: number; bullets: string[] }>;
}

/** Keyed by the job title as the hiring email names it. */
export const CV_RUBRICS: Record<string, CvJobRubric> = {
  "Bookkeeper": {
    "relevant": [
      "Bookkeeper",
      "Accounts Assistant",
      "Purchase Ledger Clerk",
      "Finance Assistant"
    ],
    "mustOk": [
      "AAT Level 3 Diploma in Accounting",
      "AAT Level 4 Diploma in Professional Accounting"
    ],
    "degreesOk": [],
    "bonus": "AAT Level 4 Diploma in Professional Accounting",
    "nice": [
      {
        "id": "xero",
        "label": "Xero used day to day",
        "pts": 4,
        "bullets": [
          "Processed supplier invoices and bank feeds in Xero every day",
          "Ran the Xero ledgers for 14 small-business clients"
        ]
      },
      {
        "id": "vat",
        "label": "VAT returns prepared and filed under Making Tax Digital",
        "pts": 5,
        "bullets": [
          "Prepared and filed quarterly VAT returns under Making Tax Digital",
          "Took over the MTD VAT returns for the group, filing every quarter on time"
        ]
      },
      {
        "id": "payroll",
        "label": "Ran a payroll",
        "pts": 4,
        "bullets": [
          "Ran the monthly payroll for 35 staff, including RTI submissions",
          "Processed weekly payroll for hourly-paid site staff"
        ]
      },
      {
        "id": "recs",
        "label": "Bank and credit-card reconciliations",
        "pts": 3,
        "bullets": [
          "Reconciled four bank accounts and the company credit cards each month",
          "Cleared a six-month backlog of bank reconciliations"
        ]
      },
      {
        "id": "yearend",
        "label": "Prepared year-end files or draft accounts",
        "pts": 5,
        "bullets": [
          "Prepared year-end files and draft accounts for the partners to review",
          "Produced draft statutory accounts for sole traders and partnerships"
        ]
      }
    ]
  },
  "Dental Nurse": {
    "relevant": [
      "Dental Nurse",
      "Senior Dental Nurse",
      "Lead Dental Nurse"
    ],
    "mustOk": [
      "GDC registered dental nurse"
    ],
    "degreesOk": [],
    "bonus": "NEBDN Certificate in Dental Radiography",
    "nice": [
      {
        "id": "implant",
        "label": "Implant nursing",
        "pts": 5,
        "bullets": [
          "Assisted on implant placements twice a week",
          "Prepared surgical set-ups for implant clinics"
        ]
      },
      {
        "id": "sedation",
        "label": "IV sedation nursing",
        "pts": 5,
        "bullets": [
          "Nursed for IV sedation sessions for anxious patients",
          "Monitored sedated patients during weekly sedation lists"
        ]
      },
      {
        "id": "ohe",
        "label": "Oral health education",
        "pts": 4,
        "bullets": [
          "Delivered oral health education sessions in local primary schools",
          "Gave patients brushing and diet advice as part of prevention appointments"
        ]
      },
      {
        "id": "decon",
        "label": "Ran the decontamination room (HTM 01-05)",
        "pts": 3,
        "bullets": [
          "Ran the decontamination room to HTM 01-05, including daily autoclave tests",
          "Led the practice decontamination audit"
        ]
      },
      {
        "id": "software",
        "label": "Used dental practice software (SOE, Dentally or R4)",
        "pts": 3,
        "bullets": [
          "Booked patients and kept clinical notes on Dentally",
          "Charted and updated records in Software of Excellence (SOE)"
        ]
      }
    ]
  },
  "Warehouse Team Leader": {
    "relevant": [
      "Warehouse Team Leader",
      "Shift Leader",
      "Warehouse Supervisor",
      "Shift Supervisor"
    ],
    "mustOk": [
      "RTITB counterbalance forklift licence",
      "ITSSAR counterbalance forklift licence"
    ],
    "degreesOk": [],
    "bonus": "IOSH Managing Safely",
    "nice": [
      {
        "id": "wms",
        "label": "Used a warehouse management system (Mintsoft, Peoplevox or similar)",
        "pts": 4,
        "bullets": [
          "Ran daily waves and stock moves in Mintsoft",
          "Allocated pick lists and cycle counts in Peoplevox"
        ]
      },
      {
        "id": "kpi",
        "label": "Improved pick rate or accuracy against a KPI",
        "pts": 5,
        "bullets": [
          "Raised the team pick rate from 95 to 120 units an hour",
          "Cut mis-picks by 40% in six months by changing the slotting"
        ]
      },
      {
        "id": "rota",
        "label": "Planned shift rotas",
        "pts": 3,
        "bullets": [
          "Planned the weekly rota for 22 operatives",
          "Built the peak-season shift plan and agency requests"
        ]
      },
      {
        "id": "training",
        "label": "Trained new starters",
        "pts": 4,
        "bullets": [
          "Trained every new starter on pick, pack and safety",
          "Wrote the induction checklist and buddy-trained agency staff"
        ]
      },
      {
        "id": "returns",
        "label": "Ran returns processing",
        "pts": 3,
        "bullets": [
          "Ran the returns bench, grading and restocking 300 returns a day",
          "Set up the returns process for a new client"
        ]
      }
    ]
  },
  "Recruitment Consultant": {
    "relevant": [
      "Recruitment Consultant",
      "Senior Recruitment Consultant",
      "Recruiter",
      "360 Recruitment Consultant"
    ],
    "mustOk": [],
    "degreesOk": [],
    "bonus": "REC Certificate in Recruitment Practice",
    "nice": [
      {
        "id": "billing",
        "label": "Billed above target",
        "pts": 5,
        "bullets": [
          "Billed 130% of target in 2025",
          "Hit or beat my billing target in 7 of 8 quarters"
        ]
      },
      {
        "id": "bd",
        "label": "Won new clients through business development",
        "pts": 5,
        "bullets": [
          "Won 9 new client accounts through cold calling and site visits",
          "Opened up a new manufacturing client worth £80k a year"
        ]
      },
      {
        "id": "temp",
        "label": "Ran a temp desk (weekly payroll of temps)",
        "pts": 4,
        "bullets": [
          "Ran a temp desk of 40+ weekly temps",
          "Managed timesheets and bookings for a busy industrial temp desk"
        ]
      },
      {
        "id": "crm",
        "label": "Used Bullhorn or a similar recruitment CRM",
        "pts": 3,
        "bullets": [
          "Kept every candidate and client record up to date in Bullhorn",
          "Built Bullhorn searches and shortlists daily"
        ]
      },
      {
        "id": "sector",
        "label": "Recruited for industrial or driving roles",
        "pts": 4,
        "bullets": [
          "Recruited HGV drivers and warehouse staff",
          "Filled forklift, production and driving roles for local manufacturers"
        ]
      }
    ]
  },
  "Area Manager (cafés)": {
    "relevant": [
      "Store Manager",
      "Cafe Manager",
      "General Manager",
      "Area Manager",
      "Multi-site Manager"
    ],
    "mustOk": [
      "Level 3 Award in Supervising Food Safety in Catering"
    ],
    "degreesOk": [],
    "bonus": "Personal Licence (APLH)",
    "nice": [
      {
        "id": "pl",
        "label": "Owned a site P&L",
        "pts": 5,
        "bullets": [
          "Ran the P&L for a £1.2m-a-year site",
          "Owned budgets and P&L for three cafés"
        ]
      },
      {
        "id": "opening",
        "label": "Opened a new site",
        "pts": 5,
        "bullets": [
          "Opened two new cafés, from fit-out to first trading week",
          "Led the launch team for a new drive-thru"
        ]
      },
      {
        "id": "labour",
        "label": "Controlled labour cost through rotas",
        "pts": 4,
        "bullets": [
          "Brought labour cost from 34% to 28% of sales through better rotas",
          "Planned rotas against forecast sales every week"
        ]
      },
      {
        "id": "audit",
        "label": "Passed food-safety audits or kept a 5-star hygiene rating",
        "pts": 4,
        "bullets": [
          "Kept a 5-star food hygiene rating through two EHO visits",
          "Scored 98% in the company food-safety audit"
        ]
      },
      {
        "id": "barista",
        "label": "Ran barista training",
        "pts": 3,
        "bullets": [
          "Trained 30 baristas to the SCA brewing standard",
          "Ran the monthly latte-art and dial-in training"
        ]
      }
    ]
  },
  "Payroll Officer": {
    "relevant": [
      "Payroll Officer",
      "Payroll Administrator",
      "Payroll Assistant",
      "Senior Payroll Administrator",
      "Payroll Specialist"
    ],
    "mustOk": [
      "CIPP Level 3 Payroll Technician Certificate",
      "CIPP Foundation Degree in Payroll Management"
    ],
    "degreesOk": [],
    "bonus": "CIPP Foundation Degree in Payroll Management",
    "nice": [
      {
        "id": "large",
        "label": "Ran payroll for 200 or more employees",
        "pts": 4,
        "bullets": [
          "Ran monthly payroll for 450 employees across six sites",
          "Processed weekly and monthly payrolls totalling 320 staff"
        ]
      },
      {
        "id": "pension",
        "label": "Handled pension auto-enrolment",
        "pts": 5,
        "bullets": [
          "Ran auto-enrolment assessments and pension uploads every month",
          "Handled opt-outs and re-enrolment for the workplace pension"
        ]
      },
      {
        "id": "rti",
        "label": "Made RTI submissions (FPS/EPS) to HMRC",
        "pts": 4,
        "bullets": [
          "Submitted FPS and EPS returns to HMRC every period",
          "Corrected RTI errors and handled HMRC notices"
        ]
      },
      {
        "id": "software",
        "label": "Used Sage Payroll or BrightPay",
        "pts": 3,
        "bullets": [
          "Processed payroll in BrightPay",
          "Ran Sage 50 Payroll for two companies"
        ]
      },
      {
        "id": "benefits",
        "label": "Handled salary sacrifice or P11D benefits",
        "pts": 4,
        "bullets": [
          "Set up salary-sacrifice cycle and EV schemes",
          "Prepared P11D and payrolled benefits"
        ]
      }
    ]
  },
  "Junior Data Analyst": {
    "relevant": [
      "Data Analyst",
      "Junior Data Analyst",
      "Insight Analyst",
      "Reporting Analyst",
      "BI Analyst"
    ],
    "mustOk": [],
    "degreesOk": [
      "BSc Mathematics",
      "BSc Economics",
      "BSc Computer Science",
      "BSc Statistics",
      "MPhys Physics",
      "BEng Mechanical Engineering"
    ],
    "bonus": "Microsoft PL-300 Power BI Data Analyst certification",
    "nice": [
      {
        "id": "sql",
        "label": "Wrote SQL queries",
        "pts": 5,
        "bullets": [
          "Wrote SQL queries against the orders database for weekly reporting",
          "Built SQL views that fed the finance dashboards"
        ]
      },
      {
        "id": "python",
        "label": "Used Python or R for analysis",
        "pts": 4,
        "bullets": [
          "Automated a monthly report in Python (pandas)",
          "Modelled customer churn in R"
        ]
      },
      {
        "id": "dash",
        "label": "Built dashboards in Power BI or Tableau",
        "pts": 4,
        "bullets": [
          "Built the Power BI sales dashboard used by the board",
          "Designed Tableau dashboards for store managers"
        ]
      },
      {
        "id": "ab",
        "label": "Analysed A/B tests",
        "pts": 4,
        "bullets": [
          "Analysed A/B tests on the checkout page",
          "Designed and read out website A/B experiments"
        ]
      },
      {
        "id": "ga",
        "label": "Used Google Analytics",
        "pts": 3,
        "bullets": [
          "Pulled web traffic and conversion data from GA4",
          "Set up Google Analytics goals and reports"
        ]
      }
    ]
  },
  "Service Coordinator": {
    "relevant": [
      "Service Coordinator",
      "Scheduler",
      "Planner",
      "Operations Coordinator",
      "Job Controller"
    ],
    "mustOk": [],
    "degreesOk": [],
    "bonus": "ILM Level 3 Certificate in Leadership and Management",
    "nice": [
      {
        "id": "software",
        "label": "Used job-management software (simPRO, Commusoft or Joblogic)",
        "pts": 4,
        "bullets": [
          "Scheduled engineers in Commusoft every day",
          "Ran the simPRO job board for 18 engineers"
        ]
      },
      {
        "id": "volume",
        "label": "Scheduled 30 or more jobs a day",
        "pts": 4,
        "bullets": [
          "Booked and routed 40-50 jobs a day",
          "Scheduled 35 reactive and planned jobs daily"
        ]
      },
      {
        "id": "gas",
        "label": "Handled Gas Safe paperwork or landlord certificates",
        "pts": 4,
        "bullets": [
          "Issued landlord gas safety certificates and chased renewals",
          "Kept Gas Safe records and CP12s audit-ready"
        ]
      },
      {
        "id": "team",
        "label": "Managed a team",
        "pts": 5,
        "bullets": [
          "Line-managed three coordinators",
          "Supervised the office team of five"
        ]
      },
      {
        "id": "parts",
        "label": "Ordered parts and managed van stock",
        "pts": 3,
        "bullets": [
          "Ordered parts from merchants and tracked van stock",
          "Set up a van-stock replenishment list"
        ]
      }
    ]
  },
  "Practice Manager": {
    "relevant": [
      "Practice Manager",
      "Assistant Practice Manager",
      "Operations Manager (healthcare)",
      "Clinic Manager"
    ],
    "mustOk": [],
    "degreesOk": [],
    "bonus": "ILM Level 5 (or CMI Level 5) in Leadership and Management",
    "nice": [
      {
        "id": "cqc",
        "label": "Led a CQC inspection",
        "pts": 5,
        "bullets": [
          "Led the practice through a CQC inspection rated Good",
          "Prepared all CQC evidence for an unannounced inspection"
        ]
      },
      {
        "id": "nhs",
        "label": "Managed an NHS contract (UDAs)",
        "pts": 5,
        "bullets": [
          "Managed the NHS contract and UDA delivery",
          "Monitored UDA targets and year-end reconciliation"
        ]
      },
      {
        "id": "hr",
        "label": "Handled HR (absence, disciplinaries)",
        "pts": 4,
        "bullets": [
          "Ran absence management and disciplinary meetings",
          "Handled HR casework for 25 staff"
        ]
      },
      {
        "id": "budget",
        "label": "Owned a budget",
        "pts": 4,
        "bullets": [
          "Owned a £900k practice budget",
          "Set and tracked the annual budget with the owner"
        ]
      },
      {
        "id": "complaints",
        "label": "Handled patient complaints",
        "pts": 3,
        "bullets": [
          "Handled patient complaints to resolution",
          "Ran the complaints log and responses"
        ]
      }
    ]
  },
  "E-commerce Marketing Executive": {
    "relevant": [
      "Marketing Executive",
      "Digital Marketing Executive",
      "E-commerce Executive",
      "Marketing Coordinator",
      "Digital Marketing Assistant"
    ],
    "mustOk": [],
    "degreesOk": [],
    "bonus": "CIM Certificate in Digital Marketing",
    "nice": [
      {
        "id": "shopify",
        "label": "Managed a Shopify store",
        "pts": 4,
        "bullets": [
          "Managed the Shopify store, product pages and promotions",
          "Migrated 600 products to Shopify"
        ]
      },
      {
        "id": "ppc",
        "label": "Ran Google Ads with a budget",
        "pts": 5,
        "bullets": [
          "Ran Google Ads with a £6k monthly budget",
          "Managed Google Shopping campaigns at a 6x ROAS"
        ]
      },
      {
        "id": "email",
        "label": "Ran email marketing (Klaviyo or Mailchimp)",
        "pts": 4,
        "bullets": [
          "Built Klaviyo flows that drove 25% of revenue",
          "Sent the weekly Mailchimp newsletter to 40k subscribers"
        ]
      },
      {
        "id": "seo",
        "label": "Did SEO work that grew organic traffic",
        "pts": 4,
        "bullets": [
          "Grew organic traffic by 60% with on-page SEO",
          "Ran keyword research and rewrote category pages for SEO"
        ]
      },
      {
        "id": "social",
        "label": "Ran paid social (Meta or TikTok ads)",
        "pts": 4,
        "bullets": [
          "Ran Meta ads for seasonal campaigns",
          "Managed TikTok and Instagram ad budgets"
        ]
      }
    ]
  }
};
