import {Component, HostListener, OnInit} from '@angular/core';
import {ActivatedRoute} from "@angular/router";
import {AccessLocation, FilterSchema, StudyFilterConfig, StudyPageConfig, DicomMode} from "../../interfaces";
import {StudyService} from "./study.service";
import {Observable} from "rxjs/Observable";
import {j4care} from "../../helpers/j4care.service";
import {Aet} from "../../models/aet";
import {PermissionService} from "../../helpers/permissions/permission.service";
import {AppService} from "../../app.service";
import { retry } from 'rxjs/operators';
import {Globalvar} from "../../constants/globalvar";
import {unescape} from "querystring";
import {animate, state, style, transition, trigger} from "@angular/animations";
import {HttpErrorHandler} from "../../helpers/http-error-handler";
import {PatientDicom} from "../../models/patient-dicom";
import {StudyDicom} from "../../models/study-dicom";
import * as _  from "lodash";
import {LoadingBarService} from "@ngx-loading-bar/core";


@Component({
    selector: 'app-study',
    templateUrl: './study.component.html',
    styleUrls: ['./study.component.scss'],
    animations:[
        trigger("showHide",[
            state("show",style({
                padding:"*",
                height:'*',
                opacity:1
            })),
            state("hide",style({
                padding:"0",
                opacity:0,
                height:'0px',
                margin:"0"
            })),
            transition("show => hide",[
                animate('0.4s')
            ]),
            transition("hide => show",[
                animate('0.3s')
            ])
        ])
    ]
})
export class StudyComponent implements OnInit {

    test = Globalvar.ORDERBY;

    isOpen = true;
    testToggle(){
        this.isOpen = !this.isOpen;
    }
    studyConfig:StudyPageConfig = {
        tab:"study",
        accessLocation:"internal"
    };

    patientAttributes;

    filter:StudyFilterConfig = {
        filterSchemaMain:{
            lineLength:undefined,
            schema:[]
        },
        filterSchemaExpand:{
            lineLength:2,
            schema:[]
        },
        filterModel:{
            limit:20,
            offset:0,
            StudySizeInKB:'1000-'
        },
        expand:false,
        quantityText:{
            count:"COUNT",
            size:"SIZE"
        }
    };

    applicationEntities = {
        aes:{
          external:[],
          internal:[]
        },
        aets:{
          external:[],
          internal:[]
        },
        aetsAreSet:false
    };

    constructor(
        private route:ActivatedRoute,
        private service:StudyService,
        private permissionService:PermissionService,
        private appService:AppService,
        private httpErrorHandler:HttpErrorHandler,
        private cfpLoadingBar:LoadingBarService
    ) { }

    ngOnInit() {
        console.log("aet",this.applicationEntities);
        this.route.params.subscribe(params => {
          this.studyConfig.tab = params.tab;
          this.getApplicationEntities();
        });
    }
    testShow = true;
    fixedHeader = false;
    patients:PatientDicom[] = [];
    moreStudies:boolean = false;

    @HostListener("window:scroll", [])
    onWindowScroll(e) {
        let html = document.documentElement;
        if(html.scrollTop > 73){
            this.fixedHeader = true;
            this.testShow = false;
        }else{
            this.fixedHeader = false;
            this.testShow = true;
        }

    }

    search(e){
        console.log("e",e);
        console.log("e", e);
        if (this.filter.filterModel.aet){
            let callingAet = new Aet(this.filter.filterModel.aet);
            let filters = _.clone(this.filter.filterModel);
            if(filters.limit){
                filters.limit++;
            }
            delete filters.aet;
            this.service.getStudies(callingAet, filters)
                .subscribe(res => {
                    if(res){
                        let index = 0;
                        let patient: PatientDicom;
                        let study: StudyDicom;
                        let patAttrs;
                        let tags = this.patientAttributes.dcmTag;

                        while (tags && (tags[index] < '00201200')) {
                            index++;
                        }
                        tags.splice(index, 0, '00201200');
                        tags.push('77770010', '77771010', '77771011', '77771012', '77771013', '77771014');

                        res.forEach((studyAttrs, index) => {
                            patAttrs = {};
                            this.service.extractAttrs(studyAttrs, tags, patAttrs);
                            if (!(patient && this.service.equalsIgnoreSpecificCharacterSet(patient.attrs, patAttrs))) {
                                patient = new PatientDicom(patAttrs, []);
                                this.patients.push(patient);
                            }
                            study = new StudyDicom(studyAttrs, patient, this.filter.filterModel.offset + index);
                            patient.studies.push(study);
                        });
                        if (this.moreStudies = (res.length > this.filter.filterModel.limit)) {
                            patient.studies.pop();
                            if (patient.studies.length === 0) {
                                this.patients.pop();
                            }
                            // this.studies.pop();
                        }
                    }else{
                        this.appService.showMsg("No Studies found!");
                    }
                    this.cfpLoadingBar.complete();
                    console.log("this.patients", this.patients);
                }, err => {
                    j4care.log("Something went wrong on search", e);
                    this.httpErrorHandler.handleError(err);
                    this.cfpLoadingBar.complete();
                });
        }else{
            this.appService.showError("Calling AET is missing!");
        }
    }

    filterChanged(){

    }

    setSchema(){
        this.filter.filterSchemaMain.lineLength = undefined;
        this.filter.filterSchemaExpand.lineLength = undefined;
        // setTimeout(()=>{
            this.filter.filterSchemaMain  = this.service.getFilterSchema(this.studyConfig.tab,  this.applicationEntities.aes[this.studyConfig.accessLocation],this.filter.quantityText,false);
            this.filter.filterSchemaExpand  = this.service.getFilterSchema(this.studyConfig.tab, this.applicationEntities.aes[this.studyConfig.accessLocation],this.filter.quantityText,true);
        // },0);
    }

    accessLocationChange(e){
        console.log("e",e.value);
        console.log("accessLocation",this.studyConfig.accessLocation);
        this.setSchema();
    }

    getPatientAttributeFilters(){
        this.service.getAttributeFilter().subscribe(patientAttributes=>{
            this.patientAttributes = patientAttributes;
        },err=>{
            j4care.log("Something went wrong on getting Patient Attributes",err);
            this.httpErrorHandler.handleError(err);
        });
    }
    getApplicationEntities(){
        if(!this.applicationEntities.aetsAreSet){
            Observable.forkJoin(
                this.service.getAes().map(aes=> aes.map(aet=> new Aet(aet))),
                this.service.getAets().map(aets=> aets.map(aet => new Aet(aet))),
            )
            .subscribe((res)=>{
                [0,1].forEach(i=>{
                    res[i] = j4care.extendAetObjectWithAlias(res[i]);
                    ["external","internal"].forEach(location=>{
                      this.applicationEntities.aes[location] = this.permissionService.filterAetDependingOnUiConfig(res[i],location);
                      this.applicationEntities.aets[location] = this.permissionService.filterAetDependingOnUiConfig(res[i],location);
                      this.applicationEntities.aetsAreSet = true;
                    })
                });
                this.setSchema();
            },(err)=>{
                this.appService.showError("Error getting AETs!");
                j4care.log("error getting aets in Study page",err);
            });
        }else{
            this.setSchema();
        }
    }
}
